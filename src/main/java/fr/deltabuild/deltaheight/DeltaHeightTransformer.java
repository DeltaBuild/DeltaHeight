package fr.deltabuild.deltaheight;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.tree.ClassNode;

public final class DeltaHeightTransformer implements IClassTransformer {

	interface Patch {
		int apply(ClassNode cn);
	}

	static final class Target {
		final String name;
		final boolean critical;
		final Patch patch;
		final byte[] replacement;

		Target(String name, boolean critical, Patch patch, byte[] replacement) {
			this.name = name;
			this.critical = critical;
			this.patch = patch;
			this.replacement = replacement;
		}
	}

	private static final Map<String, Target> TARGETS = new HashMap<String, Target>();

	private static void add(String cls, boolean critical, Patch p) {
		TARGETS.put(cls, new Target(cls, critical, p, null));
	}

	static {
		//Stockage
		TARGETS.put("net.minecraft.world.chunk.Chunk", new Target("net.minecraft.world.chunk.Chunk", true, null, resource("deltaheight/replace/net/minecraft/world/chunk/Chunk.class.bin")));
		add("net.minecraft.world.chunk.storage.AnvilChunkLoader", true, new Patches.AnvilChunkLoaderPatch());
		//Limites du monde
		add("net.minecraft.world.World", true, new Patches.WorldPatch());
		add("net.minecraft.world.WorldProvider", true, new Patches.WorldProviderPatch());
		add("net.minecraft.world.ChunkCache", false, new Patches.BoundsPatch(true));
		add("net.minecraft.world.Explosion", false, new Patches.BoundsPatch(false));
		add("net.minecraft.entity.item.EntityFallingSand", false, new Patches.BoundsPatch(false));
		add("net.minecraft.block.BlockMushroom", false, new Patches.BoundsPatch(false));
		add("net.minecraft.block.BlockLilyPad", false, new Patches.BoundsPatch(false));
		add("net.minecraft.block.BlockVine", false, new Patches.BoundsPatch(false));
		add("net.minecraft.block.BlockDoor", false, new Patches.BoundsPatch(false));
		add("net.minecraft.item.ItemBlock", false, new Patches.BoundsPatch(false));
		add("net.minecraft.server.dedicated.DedicatedServer", false, new Patches.BuildLimitPatch());
		add("net.minecraft.entity.Entity", false, new Patches.VoidPatch());
		add("net.minecraft.entity.item.EntityMinecart", false, new Patches.VoidPatch());
		//Réseau (NGClient)
		add("net.minecraft.network.packet.Packet51MapChunk", true, new Patches.Packet51Patch());
		add("net.minecraft.network.packet.Packet56MapChunks", true, new Patches.Packet56Patch());
		add("net.minecraft.network.packet.Packet14BlockDig", true, new Patches.BlockYShortPatch());
		add("net.minecraft.network.packet.Packet15Place", true, new Patches.BlockYShortPatch());
		add("net.minecraft.network.packet.Packet53BlockChange", true, new Patches.BlockYShortPatch());
		add("net.minecraft.network.packet.Packet61DoorChange", false, new Patches.Packet61Patch());
		add("net.minecraft.server.management.ServerConfigurationManager", true, new Patches.MarkerPatch());
		add("net.minecraft.server.management.PlayerInstance", true, new Patches.PlayerInstancePatch());
		//Générateurs en 16 sections
		add("net.minecraft.world.gen.ChunkProviderFlat", true, new Patches.FlatProviderPatch());
		add("org.bukkit.craftbukkit.v1_6_R3.generator.CustomChunkGenerator", true, new Patches.LegacyViewPatch());
		add("fr.nationsglory.ngupgrades.common.utils.ChunkHelper", false, new Patches.LegacyViewPatch());
		add("calclavia.lib.utility.MovementUtility", false, new Patches.LegacyViewPatch());
		String extra = System.getProperty("deltaheight.legacyView");
		if (extra != null) {
			for (String c : extra.split(",")) {
				if (c.trim().length() > 0) {
					add(c.trim(), false, new Patches.LegacyViewPatch());
				}
			}
		}
		//CraftBukkit
		add("org.bukkit.craftbukkit.v1_6_R3.CraftWorld", false, new Patches.CraftWorldPatch());
		add("org.bukkit.craftbukkit.v1_6_R3.CraftChunk", false, new Patches.CraftChunkPatch());
		add("org.bukkit.craftbukkit.v1_6_R3.block.CraftBlock", false, new Patches.CraftBlockPatch());
		add("org.bukkit.craftbukkit.v1_6_R3.CraftChunkSnapshot", false, new Patches.ChunkSnapshotPatch());
		add("org.bukkit.craftbukkit.v1_6_R3.event.CraftEventFactory", false, new Patches.InteractEventPatch());
	}

	static Collection<String> criticalTargets() {
		List<String> out = new ArrayList<String>();
		for (Target t : TARGETS.values()) {
			if (t.critical) {
				out.add(t.name);
			}
		}
		return out;
	}

	@Override
	public byte[] transform(String name, String transformedName, byte[] bytes) {
		if (bytes == null || transformedName == null) {
			return bytes;
		}
		Target t = TARGETS.get(transformedName);
		if (t == null) {
			return bytes;
		}
		try {
			if (t.replacement != null) {
				DeltaHeightAgent.log("remplacement de " + transformedName + " (24 sections, Y -64..319)");
				return t.replacement;
			}
			ClassNode cn = Asm.read(bytes);
			int n = t.patch.apply(cn);
			if (n <= 0) {
				throw new IllegalStateException("aucune modification appliquée");
			}
			DeltaHeightAgent.log("patch " + transformedName + " : " + n + " modification(s)");
			return Asm.write(cn);
		} catch (Throwable e) {
			if (t.critical) {
				DeltaHeightAgent.log("ÉCHEC CRITIQUE sur " + transformedName + " : " + e + " -> arrêt pour protéger la map");
				RuntimeException r = new RuntimeException("[DeltaHeight] patch critique impossible sur " + transformedName, e);
				throw r;
			}
			DeltaHeightAgent.log("patch ignoré sur " + transformedName + " : " + e);
			return bytes;
		}
	}

	private static byte[] resource(String path) {
		try {
			InputStream in = DeltaHeightTransformer.class.getClassLoader().getResourceAsStream(path);
			if (in == null) {
				throw new IllegalStateException("ressource absente : " + path);
			}
			try {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				int r;
				while ((r = in.read(buf)) > 0) {
					out.write(buf, 0, r);
				}
				return out.toByteArray();
			} finally {
				in.close();
			}
		} catch (Exception e) {
			throw new RuntimeException("[DeltaHeight] " + e, e);
		}
	}
}
