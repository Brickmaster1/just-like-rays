package teluri.mods.jlrays;

import org.joml.Vector3i;

public class ConeTracer26Nbs {

	private static final int[] SIGNS = new int[] { 1, -1 };
	// positive axis
	private static final Vector3i X = new Vector3i(1, 0, 0);
	private static final Vector3i Y = new Vector3i(0, 1, 0);
	private static final Vector3i Z = new Vector3i(0, 0, 1);
	// negative axis
	private static final Vector3i NX = new Vector3i(-1, 0, 0);
	private static final Vector3i NY = new Vector3i(0, -1, 0);
	private static final Vector3i NZ = new Vector3i(0, 0, -1);
	// unsigned cones
	private static final UCone[] UCONES = new UCone[] { //
			new UCone(X, Y, Z, 1, 0), new UCone(X, Z, Y, 0, 1), //
			new UCone(Y, X, Z, 0, 0), new UCone(Y, Z, X, 0, 1), //
			new UCone(Z, X, Y, 1, 1), new UCone(Z, Y, X, 1, 0),//
	};
	// all 48 cones
	private static final Cone[] CONES = genCones();

	private static Cone[] genCones() {
		Cone[] ncones = new Cone[48];
		int index = 0;
		for (UCone ucone : UCONES) {
			for (int s1 : SIGNS) {
				for (int s2 : SIGNS) {
					for (int s3 : SIGNS) { // 48 variations
						Vector3i v1 = new Vector3i(ucone.axis1).mul(s1);
						Vector3i v2 = new Vector3i(ucone.axis2).mul(s2);
						Vector3i v3 = new Vector3i(ucone.axis3).mul(s3);
						ncones[index] = new Cone(v1, v2, v3, ucone.edge1, ucone.edge2, 0 < s2, 0 < s3);
						index++;
					}
				}
			}
		}
		return ncones;
	}
	/////////////////////////////////////////////////////////////////////

	private static final Offset ZERO = new Offset(0, 0, 0);

	/**
	 * trace all 48 cones around a source
	 * 
	 * @see ConeTracer26Nbs.TraceCone
	 */
	public static void TraceAllCones(Vector3i source, int range, IAlphaProvider opmap, ILightConsumer lmap) {
		for (Cone cone : CONES) {// 48 times
			TraceCone(source, ZERO, range, cone, opmap, lmap);
		}
	}

	/**
	 * trace a 1-6 cones with an offset. this offseted cone correspond to the changes of visibility values caused by changes of a single block
	 * 
	 * @param origin
	 * @param offset
	 * @param range
	 * @param aprov
	 * @param lcons
	 */
	public static void TraceChangeCone(Vector3i origin, Vector3i offset, int range, IAlphaProvider aprov, ILightConsumer lcons) {
		Vector3i v1 = offset.x < 0 ? NX : X;
		Vector3i v2 = offset.y < 0 ? NY : Y;
		Vector3i v3 = offset.z < 0 ? NZ : Z;
		int o1 = Math.abs(offset.x);
		int o2 = Math.abs(offset.y);
		int o3 = Math.abs(offset.z);

		// ordering high to low
		if (o1 < o2) { // a = a ^ b ^ (b = a) == swap(a,b)
			o1 = o1 ^ o2 ^ (o2 = o1);
			Vector3i vtmp = v1;
			v1 = v2;
			v2 = vtmp;
		}
		if (o2 < o3) {
			o2 = o2 ^ o3 ^ (o3 = o2);
			Vector3i vtmp = v2;
			v2 = v3;
			v3 = vtmp;
		}
		if (o1 < o2) {
			o1 = o1 ^ o2 ^ (o2 = o1);
			Vector3i vtmp = v1;
			v1 = v2;
			v2 = vtmp;
		}
		Offset offset2 = new Offset(o1, o2, o3);
		Cone cone = new Cone(v1, v2, v3, false, false, false, false); // false mean priority
		TraceCone(origin, offset2, range, cone, aprov, lcons);
		// TODO this should do more than one cone in case o1==o2 and/or o2==3 and/or or3==0
		// but franckly i have no idea how to do that, especially considering that it should handle edge priority, which is done manually rn
	}

	/**
	 * compute visibility (and light received) over one cone
	 * 
	 * @param origin:   origin of the cone
	 * @param offset:   offset between the origin and the source
	 * @param emit:     emition of the source
	 * @param range:    how far should visibility by computer (to be estimated based on emit)
	 * @param v1,v2,v3: axises vectors
	 * @param opmap:    opacity provider
	 * @param lmap      visibility/light value consumer
	 */
	public static void TraceCone(Vector3i origin, Offset offset, int range, Cone cone, IAlphaProvider opmap, ILightConsumer lmap) {
		final Vector3i vit1 = new Vector3i();
		final Vector3i vit2 = new Vector3i();
		final Vector3i xyz = new Vector3i();

		// store the visibility values
		float[][] vbuffer = new float[range + 1][range + 1];
		vbuffer[0][0] = 1; // the source

		// iterate from source to range (it1)
		for (int it1 = 1; it1 <= range; it1++) { // start at 1 to skip source
			vit1.set(cone.axis1).mul(it1);
			boolean nonzero = false;
			for (int it2 = it1; 0 <= it2; it2--) {// start from the end to handle vbuffer values turnover easily
				vit2.set(cone.axis2).mul(it2).add(vit1);
				for (int it3 = it2; 0 <= it3; it3--) { // same than it2
					xyz.set(cone.axis3).mul(it3).add(vit2).add(origin); // signed distance
					// xyz.set(origin).add(sdist); // world position

					float opacity = opmap.get(xyz.x, xyz.y, xyz.z);
					if (opacity == 0) {
						vbuffer[it2][it3] = -0.0f; // below zero values mean shadows are larger around edges
						continue;
					}
					// weights adjustments to reflect the source position rather than the origin of the cone
					int of1 = offset.o1 - offset.o2;
					int of2 = offset.o2 - offset.o3;
					int of3 = offset.o3;

					// weights
					int w1 = it1 - it2 + of1;
					int w2 = it2 - it3 + of2;
					int w3 = it3 + of3;

					// neigbors
					float nb1 = w1 == of1 ? 0 : vbuffer[it2 + 0][it3 + 0] * w1;
					float nb2 = w2 == of2 ? 0 : vbuffer[it2 - 1][it3 + 0] * w2;
					float nb3 = w3 == of3 ? 0 : vbuffer[it2 - 1][it3 - 1] * w3;

					// interpolating. it1 = b1 + b2 + b3
					float visi = /* opacity* */ (nb1 + nb2 + nb3) / (it1 + offset.o1);
					visi = Math.max(visi, 0);
					// replace the nb1 neigbor (as this pos was the last time it was needed, it can be replaced)
					vbuffer[it2][it3] = visi;

					if (visi <= 0) {
						continue;
					}
					nonzero = true;
					// end of visibility computation

					// skip if doesnt have to do the edge
					if ((w1 == 0 && cone.edge1) || (w2 == 0 && cone.edge2) || (it2 == 0 && cone.qedge2) || (it3 == 0 && cone.qedge3)) {
						continue;
					}

					// light effects and output
					// udist.set(it1, it2, it3).add(offset.o1, offset.o2, offset.o3);
					double dist = Vector3i.length(it1 + offset.o1, it2 + offset.o2, it3 + offset.o3);
					lmap.consumer(xyz.x, xyz.y, xyz.z, visi, dist);
				}
			}
			if (!nonzero) {
				return;
			}
		}

	}

	@FunctionalInterface
	public static interface IAlphaProvider { // or opacity, but alpha is shorter. 0=opaque, 1=air
		float get(int x, int y, int z);
	}

	@FunctionalInterface
	public static interface ILightConsumer {
		void consumer(int x, int y, int z, float value, double distance);
	}

	private static record Cone(// signed cone
			Vector3i axis1, Vector3i axis2, Vector3i axis3, //
			boolean edge1, boolean edge2, // diagonal edges priorities
			boolean qedge2, boolean qedge3 // quadrant edges priorities
	) {}

	private static record UCone(Vector3i axis1, Vector3i axis2, Vector3i axis3, boolean edge1, boolean edge2) { // unsigned cone

		public UCone(Vector3i naxis1, Vector3i naxis2, Vector3i naxis3, int nedge1, int nedge2) {
			this(naxis1, naxis2, naxis3, nedge1 == 0, nedge2 == 0);
		}
	}

	private static record Offset(int o1, int o2, int o3) {}

}
