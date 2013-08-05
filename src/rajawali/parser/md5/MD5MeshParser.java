package rajawali.parser.md5;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.StringTokenizer;

import rajawali.animation.mesh.AAnimationObject3D;
import rajawali.animation.mesh.SkeletalAnimationChildObject3D;
import rajawali.animation.mesh.SkeletalAnimationChildObject3D.BoneVertex;
import rajawali.animation.mesh.SkeletalAnimationChildObject3D.BoneWeight;
import rajawali.animation.mesh.SkeletalAnimationFrame.SkeletonJoint;
import rajawali.animation.mesh.SkeletalAnimationObject3D;
import rajawali.animation.mesh.SkeletalAnimationObject3D.SkeletalAnimationException;
import rajawali.materials.DiffuseMaterial;
import rajawali.materials.textures.ATexture.TextureException;
import rajawali.materials.textures.Texture;
import rajawali.materials.textures.TextureManager;
import rajawali.math.vector.Vector3;
import rajawali.parser.AMeshParser;
import rajawali.parser.IAnimatedMeshParser;
import rajawali.renderer.RajawaliRenderer;
import rajawali.util.RajLog;
import android.content.res.Resources;
import android.opengl.GLES20;
import android.opengl.Matrix;

public class MD5MeshParser extends AMeshParser implements IAnimatedMeshParser {

	private static final String MD5_VERSION = "MD5Version";
	private static final String COMMAND_LINE = "commandline";

	private static final String NUM_JOINTS = "numJoints";
	private static final String NUM_MESHES = "numMeshes";
	private static final String NUM_VERTS = "numverts";
	private static final String NUM_TRIS = "numtris";
	private static final String NUM_WEIGHTS = "numweights";

	private static final String JOINTS = "joints";
	private static final String MESH = "mesh";
	private static final String SHADER = "shader";
	private static final String VERT = "vert";
	private static final String TRI = "tri";
	private static final String WEIGHT = "weight";

	private int mNumJoints;
	private int mNumMeshes;
	private int mMeshIndex = 0;

	private SkeletonMeshData[] mMeshes;
	private SkeletonJoint[] mJoints;

	public float[] mBindPoseMatrix;
	public float[][] mInverseBindPoseMatrix;

	public MD5MeshParser(RajawaliRenderer renderer, String fileOnSDCard) {
		super(renderer, fileOnSDCard);
	}

	public MD5MeshParser(RajawaliRenderer renderer, int resourceId) {
		this(renderer.getContext().getResources(), renderer.getTextureManager(), resourceId);
	}

	public MD5MeshParser(Resources resources, TextureManager textureManager, int resourceId) {
		super(resources, textureManager, resourceId);
	}

	public AAnimationObject3D getParsedAnimationObject() {
		return (AAnimationObject3D) mRootObject;
	}

	@Override
	public MD5MeshParser parse() throws ParsingException {
		super.parse();

		BufferedReader buffer = null;
		if (mFile == null) {
			InputStream fileIn = mResources.openRawResource(mResourceId);
			buffer = new BufferedReader(new InputStreamReader(fileIn));
		} else {
			try {
				buffer = new BufferedReader(new FileReader(mFile));
			} catch (FileNotFoundException e) {
				RajLog.e("[" + getClass().getCanonicalName() + "] Could not find file.");
				throw new ParsingException(e);
			}
		}
		String line;

		try {
			while ((line = buffer.readLine()) != null) {
				StringTokenizer parts = new StringTokenizer(line, " ");
				int numTokens = parts.countTokens();

				if (numTokens == 0)
					continue;
				String type = parts.nextToken();

				if (type.equalsIgnoreCase(MD5_VERSION)) {
					RajLog.d("MD5 Version: " + parts.nextToken());
				} else if (type.equalsIgnoreCase(COMMAND_LINE)) {} else if (type.equalsIgnoreCase(NUM_JOINTS)) {
					mNumJoints = Integer.parseInt(parts.nextToken());
					mJoints = new SkeletonJoint[mNumJoints];
				} else if (type.equalsIgnoreCase(NUM_MESHES)) {
					mNumMeshes = Integer.parseInt(parts.nextToken());
					mMeshes = new SkeletonMeshData[mNumMeshes];
				} else if (type.equalsIgnoreCase(JOINTS)) {
					parseJoints(buffer);
				} else if (type.equals(MESH)) {
					parseMesh(buffer);
				}
			}
			buffer.close();

			buildBindPose();
			buildMeshes();
			calculateNormals();
			createObjects();
		} catch (Exception tme) {
			try {
				buffer.close();
			} catch (Exception ex) {}
			throw new ParsingException(tme);
		} finally {
			mMeshes = null;
			mJoints = null;

			mBindPoseMatrix = null;
			mInverseBindPoseMatrix = null;
		}

		return this;
	}

	private void parseJoints(BufferedReader buffer) {
		try {
			String line;
			int count = 0;

			while ((line = buffer.readLine()) != null) {
				SkeletonJoint joint = new SkeletonJoint();
				if (line.length() == 0)
					continue;

				if (line.indexOf('}') > -1) {
					return;
				}
				line = line.replace('\t', ' ');

				// -- Bone Name
				int offset = line.lastIndexOf('"');
				joint.setName(line.substring(line.indexOf('"') + 1, offset));

				// -- Parent Index
				offset += 2;
				joint.setParentIndex(Integer.parseInt(line.substring(offset, line.indexOf(' ', offset))));

				// -- position
				offset = line.indexOf(')');
				String[] p = line.substring(line.indexOf('(') + 2, offset).split(" ");
				joint.setPosition(Float.parseFloat(p[0]), Float.parseFloat(p[2]), Float.parseFloat(p[1]));

				// -- orientation
				p = line.substring(line.indexOf('(', offset) + 2, line.lastIndexOf(')')).split(" ");
				joint.setOrientation(Float.parseFloat(p[0]), Float.parseFloat(p[2]), Float.parseFloat(p[1]));
				joint.getOrientation().computeW();

				mJoints[count++] = joint;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void parseMesh(BufferedReader buffer) {
		try {
			String line;
			SkeletonMeshData mesh = new SkeletonMeshData();

			while ((line = buffer.readLine()) != null) {
				line = line.replace("\t", "");
				StringTokenizer parts = new StringTokenizer(line, " ");
				int numTokens = parts.countTokens();
				if (line.indexOf('}') > -1) {
					mMeshes[mMeshIndex++] = mesh;
					return;
				}
				if (numTokens == 0 || line.indexOf('}') > -1)
					continue;

				String type = parts.nextToken();

				if (type.equalsIgnoreCase(SHADER)) {
					String shader = parts.nextToken();
					shader = shader.replace("\"", "");
					mesh.textureName = shader;
					if (shader.length() == 0)
						continue;

					int lastDelim = shader.lastIndexOf("/");
					if (lastDelim == -1)
						lastDelim = shader.lastIndexOf("\\");
					if (lastDelim > -1)
						mesh.textureName = shader.substring(lastDelim + 1, shader.length());

					int dot = shader.lastIndexOf(".");
					if (dot > -1)
						mesh.textureName = shader.substring(0, dot);
				} else if (type.equalsIgnoreCase(NUM_VERTS)) {
					mesh.numVertices = Integer.parseInt(parts.nextToken());
					mesh.boneVertices = new BoneVertex[mesh.numVertices];
				} else if (type.equalsIgnoreCase(VERT)) {
					int index = Integer.parseInt(parts.nextToken());
					BoneVertex vert = new BoneVertex();

					// -- ignore '('
					parts.nextToken();
					vert.textureCoordinate.setAll(Float.parseFloat(parts.nextToken()),
							Float.parseFloat(parts.nextToken()));

					// -- ignore ')'
					parts.nextToken();
					vert.weightIndex = Integer.parseInt(parts.nextToken());
					vert.numWeights = Integer.parseInt(parts.nextToken());
					mesh.numWeights += vert.numWeights;

					mesh.maxBoneWeightsPerVertex = Math.max(mesh.maxBoneWeightsPerVertex, vert.numWeights);

					mesh.boneVertices[index] = vert;
				} else if (type.equalsIgnoreCase(NUM_TRIS)) {
					mesh.numTriangles = Integer.parseInt(parts.nextToken());
					mesh.triangles = new int[mesh.numTriangles][];
				} else if (type.equalsIgnoreCase(TRI)) {
					int index = Integer.parseInt(parts.nextToken());
					mesh.triangles[index] = new int[] { Integer.parseInt(parts.nextToken()),
							Integer.parseInt(parts.nextToken()), Integer.parseInt(parts.nextToken()) };
				} else if (type.equalsIgnoreCase(NUM_WEIGHTS)) {
					mesh.numWeights = Integer.parseInt(parts.nextToken());
					mesh.boneWeights = new BoneWeight[mesh.numWeights];
				} else if (type.equalsIgnoreCase(WEIGHT)) {
					int index = Integer.parseInt(parts.nextToken());

					BoneWeight weight = new BoneWeight();
					weight.jointIndex = Integer.parseInt(parts.nextToken());
					weight.weightValue = Float.parseFloat(parts.nextToken());

					mesh.boneWeights[index] = weight;

					// -- ignore '('
					parts.nextToken();
					float x = Float.parseFloat(parts.nextToken());
					float z = Float.parseFloat(parts.nextToken());
					float y = Float.parseFloat(parts.nextToken());
					weight.position.setAll(x, y, z);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void buildMeshes() {
		for (int i = 0; i < mNumMeshes; ++i) {
			int boneIndex = 0;
			SkeletonMeshData mesh = mMeshes[i];
			mesh.vertices = new float[mesh.numVertices * 3];
			mesh.indices = new int[mesh.numWeights];
			mesh.weights = new float[mesh.numWeights];
			mesh.textureCoordinates = new float[mesh.numVertices * 2];

			int numVerts = mesh.numVertices;

			for (int j = 0; j < numVerts; ++j) {
				BoneVertex vert = mesh.boneVertices[j];
				Vector3 position = new Vector3();

				for (int k = 0; k < vert.numWeights; ++k) {
					BoneWeight weight = mesh.boneWeights[vert.weightIndex + k];
					SkeletonJoint joint = mJoints[weight.jointIndex];

					Vector3 rotPos = joint.getOrientation().multiply(weight.position);

					Vector3 pos = Vector3.addAndCreate(joint.getPosition(), rotPos);
					pos.multiply(weight.weightValue);
					position.add(pos);

					mesh.indices[boneIndex] = weight.jointIndex;
					mesh.weights[boneIndex++] = weight.weightValue;
				}

				int vertIndex = j * 3;
				mesh.vertices[vertIndex] = position.x;
				mesh.vertices[vertIndex + 1] = position.y;
				mesh.vertices[vertIndex + 2] = position.z;

				int uvIndex = j * 2;
				mesh.textureCoordinates[uvIndex] = vert.textureCoordinate.getX();
				mesh.textureCoordinates[uvIndex + 1] = vert.textureCoordinate.getY();
			}
		}
	}

	private void calculateNormals() {
		for (int i = 0; i < mNumMeshes; ++i) {
			SkeletonMeshData mesh = mMeshes[i];
			int numTriangles = mesh.numTriangles;

			mesh.indices = new int[numTriangles * 3];
			int index = 0;

			for (int j = 0; j < numTriangles; ++j) {
				int[] triangle = mesh.triangles[j];
				int index0 = triangle[0];
				int index1 = triangle[1];
				int index2 = triangle[2];

				mesh.indices[index++] = index0;
				mesh.indices[index++] = index1;
				mesh.indices[index++] = index2;

				int index03 = index0 * 3;
				int index13 = index1 * 3;
				int index23 = index2 * 3;

				Vector3 v0 = new Vector3(mesh.vertices[index03], mesh.vertices[index03 + 1], mesh.vertices[index03 + 2]);
				Vector3 v1 = new Vector3(mesh.vertices[index13], mesh.vertices[index13 + 1], mesh.vertices[index13 + 2]);
				Vector3 v2 = new Vector3(mesh.vertices[index23], mesh.vertices[index23 + 1], mesh.vertices[index23 + 2]);

				Vector3 normal = Vector3.crossAndCreate(Vector3.subtractAndCreate(v2, v0), Vector3.subtractAndCreate(v1, v0));

				mesh.boneVertices[index0].normal.add(normal);
				mesh.boneVertices[index1].normal.add(normal);
				mesh.boneVertices[index2].normal.add(normal);
			}

			int numVertices = mesh.numVertices;
			if (mesh.normals == null)
				mesh.normals = new float[numVertices * 3];

			for (int j = 0; j < numVertices; ++j) {
				BoneVertex vert = mesh.boneVertices[j];
				Vector3 normal = vert.normal.clone();
				vert.normal.normalize();

				normal.normalize();

				int normIndex = j * 3;
				mesh.normals[normIndex] = normal.x;
				mesh.normals[normIndex + 1] = normal.y;
				mesh.normals[normIndex + 2] = normal.z;

				vert.normal.setAll(0, 0, 0);

				// -- bind-pose normal to joint-local
				// so the animated normal can be computed faster
				for (int k = 0; k < vert.numWeights; ++k) {
					BoneWeight weight = mesh.boneWeights[vert.weightIndex + k];
					SkeletonJoint joint = mJoints[weight.jointIndex];
					vert.normal.add(Vector3.scaleAndCreate(joint.getOrientation().multiply(normal), weight.weightValue));
				}
			}
		}
	}

	private void buildBindPose() {
		mBindPoseMatrix = new float[mNumJoints * 16];
		mInverseBindPoseMatrix = new float[mNumJoints][];

		for (int i = 0; i < mNumJoints; ++i) {
			SkeletonJoint joint = mJoints[i];

			float[] boneTranslation = new float[16];
			float[] boneRotation = new float[16];
			float[] boneMatrix = new float[16];
			float[] inverseBoneMatrix = new float[16];

			Matrix.setIdentityM(boneTranslation, 0);
			Matrix.setIdentityM(boneRotation, 0);

			Vector3 jointPos = joint.getPosition();

			Matrix.translateM(boneTranslation, 0, jointPos.x, jointPos.y, jointPos.z);
			joint.getOrientation().toRotationMatrix(boneRotation);

			Matrix.multiplyMM(boneMatrix, 0, boneTranslation, 0, boneRotation, 0);
			Matrix.invertM(inverseBoneMatrix, 0, boneMatrix, 0);

			for (int j = 0; j < 16; j++) {
				mBindPoseMatrix[i + j] = boneMatrix[j];
			}
			mInverseBindPoseMatrix[i] = inverseBoneMatrix;
		}
	}

	private void createObjects() throws TextureException, ParsingException, SkeletalAnimationException {
		SkeletalAnimationObject3D root = new SkeletalAnimationObject3D();
		root.uBoneMatrix = mBindPoseMatrix;
		root.mInverseBindPoseMatrix = mInverseBindPoseMatrix;
		root.setJoints(mJoints);
		mRootObject = root;
		for (int i = 0; i < mNumMeshes; ++i) {
			SkeletonMeshData mesh = mMeshes[i];
			SkeletalAnimationChildObject3D o = new SkeletalAnimationChildObject3D();
			o.setData(
					mesh.vertices, GLES20.GL_STREAM_DRAW,
					mesh.normals, GLES20.GL_STREAM_DRAW,
					mesh.textureCoordinates, GLES20.GL_STATIC_DRAW,
					null, GLES20.GL_STATIC_DRAW,
					mesh.indices, GLES20.GL_STATIC_DRAW
					);
			o.setMaxBoneWeightsPerVertex(mesh.maxBoneWeightsPerVertex);
			o.setSkeletonMeshData(mesh.numVertices, mesh.boneVertices, mesh.numWeights, mesh.boneWeights);
			o.setName("MD5Mesh_" + i);
			o.setSkeleton(mRootObject);

			boolean hasTexture = mesh.textureName != null && mesh.textureName.length() > 0;
// TODO
			/*
			DiffuseMaterial mat = new DiffuseMaterial();
			mat.setSkeletalAnimationEnabled(true);
			mat.setNumJoints(mNumJoints);
			mat.setMaxWeights(mesh.maxBoneWeightsPerVertex);
			o.setMaterial(mat);
			if (!hasTexture) {
				mat.setUseSingleColor(!hasTexture);
				o.setColor(0xff000000 + (int) (Math.random() * 0xffffff));
			} else {
				int identifier = mResources.getIdentifier(mesh.textureName, "drawable",
						mResources.getResourcePackageName(mResourceId));
				if (identifier == 0) {
					throw new ParsingException("Couldn't find texture " + mesh.textureName);
				}
				mat.addTexture(new Texture(identifier));
			}
*/
			mRootObject.addChild(o);
			
			mesh.destroy();
			mesh = null;
		}
	}

	private class SkeletonMeshData {
		public String textureName;
		public int numVertices;
		public int numTriangles;
		public int numWeights;
		public int maxBoneWeightsPerVertex;
		public BoneVertex[] boneVertices;
		public BoneWeight[] boneWeights;
		public int[][] triangles;
		public float[] vertices;
		public float[] normals;
		public int[] indices;
		public float[] textureCoordinates;
		public float[] weights;
		
		public void destroy()
		{
			boneVertices = null;
			boneWeights = null;
			triangles = null;
			vertices = null;
			normals = null;
			indices = null;
			textureCoordinates = null;
			weights = null;
		}
	}
}
