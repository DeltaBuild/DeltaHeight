package fr.deltabuild.deltaheight;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;

public final class DeltaHeightAgent {
	//Version lue dans le manifeste
	public static final String VERSION = version();
	static final boolean DEBUG = Boolean.getBoolean("deltaheight.debug");

	private DeltaHeightAgent() {
	}

	public static void premain(String args, Instrumentation inst) {
		log("DeltaHeight " + VERSION + " : monde étendu Y " + -64 + ".." + 319 + " (24 sections), protocole NGClient (marqueur NGNEID)");
		inst.addTransformer(new LaunchWatcher());
	}

	public static void agentmain(String args, Instrumentation inst) {
		premain(args, inst);
	}

	private static String version() {
		String v = DeltaHeightAgent.class.getPackage().getImplementationVersion();
		return v != null ? v : "dev";
	}

	static void log(String s) {
		System.out.println("[DeltaHeight] " + s);
	}

	static void debug(String s) {
		if (DEBUG) {
			System.out.println("[DeltaHeight/debug] " + s);
		}
	}

	//Installation du transformer après la déobfuscation FML
	static final class LaunchWatcher implements ClassFileTransformer {
		private volatile boolean installed;

		@Override
		public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
			if (className != null && PluginPatches.handles(className)) {
				return PluginPatches.transform(className, bytes);
			}
			if (!this.installed && loader != null && "net.minecraft.launchwrapper.LaunchClassLoader".equals(loader.getClass().getName())) {
				try {
					this.tryInstall(loader);
				} catch (Throwable t) {
					log("ERREUR installation du transformer : " + t);
					t.printStackTrace();
				}
			}
			if ("net/minecraft/world/chunk/Chunk".equals(className) && loader != null && "net.minecraft.launchwrapper.LaunchClassLoader".equals(loader.getClass().getName())) {
				//Sécurité : Chunk non remplacé = arrêt du serveur
				if (bytes == null || !contains(bytes, "dhLegacyPending")) {
					log("ERREUR FATALE : la classe Chunk n'a pas été remplacée (transformer installé=" + this.installed + "). Arrêt du serveur pour protéger la map.");
					Runtime.getRuntime().halt(42);
				}
			}
			return null;
		}

		private static boolean contains(byte[] data, String s) {
			byte[] p = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
			outer:
			for (int i = 0; i <= data.length - p.length; ++i) {
				for (int j = 0; j < p.length; ++j) {
					if (data[i + j] != p[j]) {
						continue outer;
					}
				}
				return true;
			}
			return false;
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		private synchronized void tryInstall(ClassLoader lcl) throws Exception {
			if (this.installed) {
				return;
			}
			Field f = lcl.getClass().getDeclaredField("transformers");
			f.setAccessible(true);
			List list = (List) f.get(lcl);
			int deobf = -1;
			for (int i = 0; i < list.size(); ++i) {
				Object t = list.get(i);
				if (t instanceof DeltaHeightTransformer) {
					this.installed = true;
					return;
				}
				if (t != null && t.getClass().getName().endsWith("DeobfuscationTransformer")) {
					deobf = i;
				}
			}
			if (deobf < 0) {
				return; //Déobfuscation pas encore chargée
			}
			list.add(deobf + 1, new DeltaHeightTransformer());
			this.installed = true;
			log("transformer installé après " + list.get(deobf).getClass().getName() + " (position " + (deobf + 1) + "/" + list.size() + ")");
			if (DEBUG) {
				for (int i = 0; i < list.size(); ++i) {
					debug("  transformer[" + i + "] = " + list.get(i).getClass().getName());
				}
			}
			//Vérification des classes déjà chargées
			try {
				Field cf = lcl.getClass().getDeclaredField("cachedClasses");
				cf.setAccessible(true);
				Map cached = (Map) cf.get(lcl);
				for (String target : DeltaHeightTransformer.criticalTargets()) {
					if (cached.containsKey(target)) {
						log("ATTENTION : " + target + " était déjà chargé avant DeltaHeight -> patch impossible !");
					}
				}
			} catch (Throwable ignored) {
			}
		}
	}
}
