package net.minecraft.world.chunk;

import gnu.trove.map.hash.TObjectIntHashMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.command.IEntitySelector;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityEvent.EnteringChunk;
import net.minecraftforge.event.world.ChunkEvent.Load;
import net.minecraftforge.event.world.ChunkEvent.Unload;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.v1_6_R3.CraftChunk;
import org.bukkit.craftbukkit.v1_6_R3.util.UnsafeList;
import za.co.mcportcentral.MCPCConfig.Setting;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class Chunk {
	//Constantes
	public static final int DH_MIN_Y = -64;
	public static final int DH_MAX_Y = 320;
	public static final int DH_SECTIONS = 24;
	public static final int DH_OFFSET = 4; //Sections sous Y 0
	public static final int DH_LEGACY_SECTIONS = 16;
	private static final int DH_RELIGHT_TOTAL = DH_SECTIONS * 256;
	//Capacité de départ des listes d'entités (32 en vanilla)
	private static final int DH_ENTITY_LIST_CAPACITY = 4;
	public static final String DH_MARKER_CHANNEL = "NGNEID";
	public static final String DH_NBT_TAG = "DeltaHeight";

	public static boolean field_76640_a;
	public ExtendedBlockStorage[] field_76652_q;
	public byte[] field_76651_r;
	public int[] field_76638_b;
	public boolean[] field_76639_c;
	public boolean field_76636_d;
	public World field_76637_e;
	public int[] field_76634_f;
	public final int field_76635_g;
	public final int field_76647_h;
	public boolean field_76650_s;
	public Map field_76648_i;
	public List[] field_76645_j;
	public boolean field_76646_k;
	public boolean field_76643_l;
	public boolean field_76644_m;
	public long field_76641_n;
	public boolean field_76642_o;
	public int field_82912_p;
	public long field_111204_q;
	public int field_76649_t;
	public TObjectIntHashMap<Class> entityCount;
	public int lastAccessedTick;
	public org.bukkit.Chunk bukkitChunk;
	public boolean mustSave;

	//Vue 16 sections pour le code legacy
	public ExtendedBlockStorage[] dhLegacyPending;

	private ExtendedBlockStorage dhSectionAt(int y) {
		if (this.dhLegacyPending != null) {
			this.dhCommitLegacy();
		}
		int i = (y >> 4) + DH_OFFSET;
		ExtendedBlockStorage[] s = this.field_76652_q;
		return i >= 0 && i < s.length ? s[i] : null;
	}

	private ExtendedBlockStorage dhSectionOrCreate(int y) {
		if (this.dhLegacyPending != null) {
			this.dhCommitLegacy();
		}
		int i = (y >> 4) + DH_OFFSET;
		ExtendedBlockStorage[] s = this.field_76652_q;
		if (i < 0 || i >= s.length) {
			return null;
		}
		ExtendedBlockStorage e = s[i];
		if (e == null) {
			e = s[i] = new ExtendedBlockStorage(y >> 4 << 4, !this.field_76637_e.field_73011_w.field_76576_e);
		}
		return e;
	}

	public static ExtendedBlockStorage[] dhLegacyView(Chunk chunk) {
		if (chunk.dhLegacyPending == null) {
			ExtendedBlockStorage[] v = new ExtendedBlockStorage[DH_LEGACY_SECTIONS];
			System.arraycopy(chunk.field_76652_q, DH_OFFSET, v, 0, Math.min(DH_LEGACY_SECTIONS, chunk.field_76652_q.length - DH_OFFSET));
			chunk.dhLegacyPending = v;
		}
		return chunk.dhLegacyPending;
	}

	public void dhCommitLegacy() {
		ExtendedBlockStorage[] v = this.dhLegacyPending;
		if (v != null) {
			this.dhLegacyPending = null;
			for (int i = 0; i < v.length && i + DH_OFFSET < this.field_76652_q.length; ++i) {
				if (v[i] != null && v[i] != this.field_76652_q[i + DH_OFFSET]) {
					this.field_76652_q[i + DH_OFFSET] = v[i];
				}
			}
		}
	}

	private static ExtendedBlockStorage[] dhNormalize(ExtendedBlockStorage[] in) {
		if (in == null) {
			return new ExtendedBlockStorage[DH_SECTIONS];
		}
		if (in.length == DH_SECTIONS) {
			return in;
		}
		//Tableau legacy : on replace les sections selon leur Y
		ExtendedBlockStorage[] out = new ExtendedBlockStorage[DH_SECTIONS];
		for (int i = 0; i < in.length; ++i) {
			ExtendedBlockStorage e = in[i];
			if (e != null) {
				int idx = (e.func_76662_d() >> 4) + DH_OFFSET;
				if (idx >= 0 && idx < DH_SECTIONS) {
					out[idx] = e;
				}
			}
		}
		return out;
	}

	private static int dhEntityListIndex(int sectionY, int len) {
		int i = sectionY + DH_OFFSET;
		if (i < 0) {
			i = 0;
		}
		if (i >= len) {
			i = len - 1;
		}
		return i;
	}

	//Hooks

	//Correction de la heightmap des chunks vanilla
	public static void dhOnLoad(Chunk chunk, NBTTagCompound level) {
		if (chunk == null) {
			return;
		}
		chunk.field_76652_q = dhNormalize(chunk.field_76652_q);
		if (level != null && !level.func_74764_b(DH_NBT_TAG) && !dhHasExtendedSection(chunk.field_76652_q)) {
			int[] hm = chunk.field_76634_f;
			if (hm != null && hm.length == 256) {
				for (int i = 0; i < 256; ++i) {
					if (hm[i] <= 0) {
						hm[i] = DH_MIN_Y;
					}
				}
			}
		}
		int[] hm = chunk.field_76634_f;
		if (hm == null || hm.length != 256) {
			chunk.field_76634_f = new int[256];
			Arrays.fill(chunk.field_76634_f, DH_MIN_Y);
		}
	}

	private static boolean dhHasExtendedSection(ExtendedBlockStorage[] s) {
		for (int i = 0; i < s.length; ++i) {
			if (s[i] != null && (i < DH_OFFSET || i >= DH_OFFSET + DH_LEGACY_SECTIONS)) {
				return true;
			}
		}
		return false;
	}

	public static void dhOnSave(Chunk chunk, NBTTagCompound level) {
		if (chunk != null) {
			chunk.dhCommitLegacy();
		}
		if (level != null) {
			level.func_74774_a(DH_NBT_TAG, (byte) 1);
		}
	}

	//Marqueur NGNEID pour NGClient
	public static Packet dhMarkerPacket() {
		return new Packet250CustomPayload(DH_MARKER_CHANNEL, new byte[] {1});
	}

	//PlayerInstance : position encodée avec Y complet

	public static int dhEncodePos(int x, int y, int z) {
		return (x & 15) << 12 | (z & 15) << 8 | (y & 255) | ((y - DH_MIN_Y) & 0xFFFF) << 16;
	}

	public static int dhDecodeY(int packed) {
		return ((packed >>> 16) & 0xFFFF) + DH_MIN_Y;
	}

	//Packet52 seulement si tous les Y sont entre 0 et 255
	public static void dhMultiBlock(net.minecraft.server.management.PlayerInstance instance, int chunkX, int chunkZ, int[] packed, int count, World world) {
		boolean legacy = true;
		for (int i = 0; i < count; ++i) {
			int y = dhDecodeY(packed[i]);
			if (y < 0 || y > 255) {
				legacy = false;
				break;
			}
		}
		if (legacy) {
			short[] s = new short[count];
			for (int i = 0; i < count; ++i) {
				s[i] = (short) (packed[i] & 0xFFFF);
			}
			instance.func_73256_a(new net.minecraft.network.packet.Packet52MultiBlockChange(chunkX, chunkZ, s, count, world));
		} else {
			for (int i = 0; i < count; ++i) {
				int p = packed[i];
				int x = chunkX * 16 + (p >> 12 & 15);
				int z = chunkZ * 16 + (p >> 8 & 15);
				instance.func_73256_a(new net.minecraft.network.packet.Packet53BlockChange(x, dhDecodeY(p), z, world));
			}
		}
	}

	//max-build-height : 256 -> 320
	public static int dhBuildLimit(int configured) {
		if (configured == 256) {
			return DH_MAX_Y;
		}
		return configured < 64 ? 64 : (configured > DH_MAX_Y ? DH_MAX_Y : configured);
	}

	public static int dhAuxSfx(int sfx, int y) {
		return y < 0 || y > 255 ? 0 : sfx;
	}

	//Superflat : bedrock à -64, pierre de -63 à 0

	public static final int DH_FLAT_BOTTOM = 7;
	public static final int DH_FLAT_FILL = 1;

	public static void dhFlatExtend(Chunk chunk) {
		if (chunk == null) {
			return;
		}
		if (chunk.dhLegacyPending != null) {
			chunk.dhCommitLegacy();
		}
		ExtendedBlockStorage[] s = chunk.field_76652_q;
		boolean sky = !chunk.field_76637_e.field_73011_w.field_76576_e;
		for (int i = 0; i < DH_OFFSET && i < s.length; ++i) {
			ExtendedBlockStorage e = s[i];
			if (e == null) {
				//Section neuve
				e = s[i] = new ExtendedBlockStorage((i << 4) + DH_MIN_Y, sky);
				byte[] ids = e.func_76658_g();
				if (i == 0) {
					Arrays.fill(ids, 0, 256, (byte) DH_FLAT_BOTTOM); //Bedrock Y -64
					Arrays.fill(ids, 256, ids.length, (byte) DH_FLAT_FILL);
				} else {
					Arrays.fill(ids, (byte) DH_FLAT_FILL);
				}
				e.func_76672_e();
			} else {
				//Section existante
				for (int y = 0; y < 16; ++y) {
					int id = i == 0 && y == 0 ? DH_FLAT_BOTTOM : DH_FLAT_FILL;
					for (int z = 0; z < 16; ++z) {
						for (int x = 0; x < 16; ++x) {
							e.func_76655_a(x, y, z, id);
							e.func_76654_b(x, y, z, 0);
						}
					}
				}
			}
		}
		ExtendedBlockStorage zero = DH_OFFSET < s.length ? s[DH_OFFSET] : null;
		if (zero != null) {
			for (int z = 0; z < 16; ++z) {
				for (int x = 0; x < 16; ++x) {
					if (zero.func_76656_a(x, 0, z) == DH_FLAT_BOTTOM) {
						zero.func_76655_a(x, 0, z, DH_FLAT_FILL);
						zero.func_76654_b(x, 0, z, 0);
					}
				}
			}
		}
	}

	public Chunk(World par1World, int par2, int par3) {
		this.entityCount = new TObjectIntHashMap();
		this.field_76652_q = new ExtendedBlockStorage[DH_SECTIONS];
		this.field_76651_r = new byte[256];
		this.field_76638_b = new int[256];
		this.field_76639_c = new boolean[256];
		this.field_76648_i = new HashMap();
		this.field_76649_t = DH_RELIGHT_TOTAL;
		this.field_76645_j = new List[DH_SECTIONS];
		this.field_76637_e = par1World;
		this.field_76635_g = par2;
		this.field_76647_h = par3;
		this.field_76634_f = new int[256];
		Arrays.fill(this.field_76634_f, DH_MIN_Y);

		for (int k = 0; k < this.field_76645_j.length; ++k) {
			this.field_76645_j[k] = new UnsafeList(DH_ENTITY_LIST_CAPACITY);
		}

		Arrays.fill(this.field_76638_b, -999);
		Arrays.fill(this.field_76651_r, (byte) -1);
		if (!(this instanceof EmptyChunk)) {
			this.bukkitChunk = new CraftChunk(this);
		}
	}

	public Chunk(World par1World, byte[] par2ArrayOfByte, int par3, int par4) {
		this(par1World, par3, par4);
		int k = par2ArrayOfByte.length / 256;

		for (int l = 0; l < 16; ++l) {
			for (int i1 = 0; i1 < 16; ++i1) {
				for (int j1 = 0; j1 < k; ++j1) {
					int b0 = par2ArrayOfByte[l << 11 | i1 << 7 | j1] & 255;
					if (b0 != 0) {
						ExtendedBlockStorage e = this.dhSectionOrCreate(j1);
						if (e != null) {
							e.func_76655_a(l, j1 & 15, i1, b0);
						}
					}
				}
			}
		}
	}

	public Chunk(World world, byte[] ids, byte[] metadata, int chunkX, int chunkZ) {
		this(world, chunkX, chunkZ);
		int k = ids.length / 256;

		for (int x = 0; x < 16; ++x) {
			for (int z = 0; z < 16; ++z) {
				for (int y = 0; y < k; ++y) {
					int idx = x << 11 | z << 7 | y;
					int id = ids[idx] & 255;
					byte meta = metadata[idx];
					if (id != 0) {
						ExtendedBlockStorage e = this.dhSectionOrCreate(y);
						if (e != null) {
							e.func_76655_a(x, y & 15, z, id);
							e.func_76654_b(x, y & 15, z, meta);
						}
					}
				}
			}
		}
	}

	public Chunk(World world, short[] ids, byte[] metadata, int chunkX, int chunkZ) {
		this(world, chunkX, chunkZ);
		int max = ids.length / 256;

		for (int y = 0; y < max; ++y) {
			for (int z = 0; z < 16; ++z) {
				for (int x = 0; x < 16; ++x) {
					int idx = y << 8 | z << 4 | x;
					int id = ids[idx] & 16777215;
					byte meta = metadata[idx];
					if (id != 0) {
						ExtendedBlockStorage e = this.dhSectionOrCreate(y);
						if (e != null) {
							e.func_76655_a(x, y & 15, z, id);
							e.func_76654_b(x, y & 15, z, meta);
						}
					}
				}
			}
		}
	}

	public boolean func_76600_a(int par1, int par2) {
		return par1 == this.field_76635_g && par2 == this.field_76647_h;
	}

	public int func_76611_b(int par1, int par2) {
		return this.field_76634_f[par2 << 4 | par1];
	}

	public int func_76625_h() {
		if (this.dhLegacyPending != null) {
			this.dhCommitLegacy();
		}
		for (int i = this.field_76652_q.length - 1; i >= 0; --i) {
			if (this.field_76652_q[i] != null) {
				return this.field_76652_q[i].func_76662_d();
			}
		}
		return 0;
	}

	public ExtendedBlockStorage[] func_76587_i() {
		if (this.dhLegacyPending != null) {
			this.dhCommitLegacy();
		}
		return this.field_76652_q;
	}

	//generateSkylightMap
	public void func_76603_b() {
		if (this.dhLegacyPending != null) {
			this.dhCommitLegacy();
		}
		int i = this.func_76625_h();
		this.field_82912_p = Integer.MAX_VALUE;
		boolean sky = !this.field_76637_e.field_73011_w.field_76576_e;

		int j;
		int k;
		for (j = 0; j < 16; ++j) {
			for (k = 0; k < 16; ++k) {
				this.field_76638_b[j + (k << 4)] = -999;
				int height = DH_MIN_Y;

				for (int l = i + 16; l > DH_MIN_Y; --l) {
					if (this.func_76596_b(j, l - 1, k) != 0) {
						height = l;
						break;
					}
				}

				this.field_76634_f[k << 4 | j] = height;
				if (height < this.field_82912_p) {
					this.field_82912_p = height;
				}

				if (sky) {
					int light = 15;
					int y = i + 16 - 1;

					do {
						light -= this.func_76596_b(j, y, k);
						if (light > 0) {
							ExtendedBlockStorage e = this.dhSectionAt(y);
							if (e != null) {
								e.func_76657_c(j, y & 15, k, light);
								this.field_76637_e.func_72902_n((this.field_76635_g << 4) + j, y, (this.field_76647_h << 4) + k);
							}
						}
						--y;
					} while (y > DH_MIN_Y && light > 0);
				}
			}
		}

		this.field_76643_l = true;

		for (j = 0; j < 16; ++j) {
			for (k = 0; k < 16; ++k) {
				this.func_76595_e(j, k);
			}
		}
	}

	private void func_76595_e(int par1, int par2) {
		this.field_76639_c[par1 + par2 * 16] = true;
		this.field_76650_s = true;
	}

	private void func_76593_q() {
		this.field_76637_e.field_72984_F.func_76320_a("recheckGaps");
		if (this.field_76637_e.func_72873_a(this.field_76635_g * 16 + 8, 0, this.field_76647_h * 16 + 8, 16)) {
			for (int i = 0; i < 16; ++i) {
				for (int j = 0; j < 16; ++j) {
					if (this.field_76639_c[i + j * 16]) {
						this.field_76639_c[i + j * 16] = false;
						int k = this.func_76611_b(i, j);
						int l = this.field_76635_g * 16 + i;
						int i1 = this.field_76647_h * 16 + j;
						int j1 = this.field_76637_e.func_82734_g(l - 1, i1);
						int k1 = this.field_76637_e.func_82734_g(l + 1, i1);
						int l1 = this.field_76637_e.func_82734_g(l, i1 - 1);
						int i2 = this.field_76637_e.func_82734_g(l, i1 + 1);
						if (k1 < j1) {
							j1 = k1;
						}
						if (l1 < j1) {
							j1 = l1;
						}
						if (i2 < j1) {
							j1 = i2;
						}

						this.func_76599_g(l, i1, j1);
						this.func_76599_g(l - 1, i1, k);
						this.func_76599_g(l + 1, i1, k);
						this.func_76599_g(l, i1 - 1, k);
						this.func_76599_g(l, i1 + 1, k);
					}
				}
			}

			this.field_76650_s = false;
		}

		this.field_76637_e.field_72984_F.func_76319_b();
	}

	private void func_76599_g(int par1, int par2, int par3) {
		int l = this.field_76637_e.func_72976_f(par1, par2);
		if (l > par3) {
			this.func_76609_d(par1, par2, par3, l + 1);
		} else if (l < par3) {
			this.func_76609_d(par1, par2, l, par3 + 1);
		}
	}

	private void func_76609_d(int par1, int par2, int par3, int par4) {
		if (par4 > par3 && this.field_76637_e.func_72873_a(par1, 0, par2, 16)) {
			if (par3 < DH_MIN_Y) {
				par3 = DH_MIN_Y;
			}
			if (par4 > DH_MAX_Y) {
				par4 = DH_MAX_Y;
			}
			for (int i1 = par3; i1 < par4; ++i1) {
				this.field_76637_e.func_72936_c(EnumSkyBlock.Sky, par1, i1, par2);
			}

			this.field_76643_l = true;
		}
	}

	//relightBlock
	private void func_76615_h(int par1, int par2, int par3) {
		int l = this.field_76634_f[par3 << 4 | par1];
		int i1 = l;
		if (par2 > l) {
			i1 = par2;
		}

		while (i1 > DH_MIN_Y && this.func_76596_b(par1, i1 - 1, par3) == 0) {
			--i1;
		}

		if (i1 != l) {
			this.field_76637_e.func_72975_g(par1 + this.field_76635_g * 16, par3 + this.field_76647_h * 16, i1, l);
			this.field_76634_f[par3 << 4 | par1] = i1;
			int j1 = this.field_76635_g * 16 + par1;
			int k1 = this.field_76647_h * 16 + par3;
			int l1;
			int i2;
			if (!this.field_76637_e.field_73011_w.field_76576_e) {
				ExtendedBlockStorage e;
				if (i1 < l) {
					for (l1 = i1; l1 < l; ++l1) {
						e = this.dhSectionAt(l1);
						if (e != null) {
							e.func_76657_c(par1, l1 & 15, par3, 15);
							this.field_76637_e.func_72902_n((this.field_76635_g << 4) + par1, l1, (this.field_76647_h << 4) + par3);
						}
					}
				} else {
					for (l1 = l; l1 < i1; ++l1) {
						e = this.dhSectionAt(l1);
						if (e != null) {
							e.func_76657_c(par1, l1 & 15, par3, 0);
							this.field_76637_e.func_72902_n((this.field_76635_g << 4) + par1, l1, (this.field_76647_h << 4) + par3);
						}
					}
				}

				l1 = 15;

				while (i1 > DH_MIN_Y && l1 > 0) {
					--i1;
					i2 = this.func_76596_b(par1, i1, par3);
					if (i2 == 0) {
						i2 = 1;
					}

					l1 -= i2;
					if (l1 < 0) {
						l1 = 0;
					}

					ExtendedBlockStorage e1 = this.dhSectionAt(i1);
					if (e1 != null) {
						e1.func_76657_c(par1, i1 & 15, par3, l1);
					}
				}
			}

			l1 = this.field_76634_f[par3 << 4 | par1];
			i2 = l;
			int var12 = l1;
			if (l1 < l) {
				i2 = l1;
				var12 = l;
			}

			if (l1 < this.field_82912_p) {
				this.field_82912_p = l1;
			}

			if (!this.field_76637_e.field_73011_w.field_76576_e) {
				this.func_76609_d(j1 - 1, k1, i2, var12);
				this.func_76609_d(j1 + 1, k1, i2, var12);
				this.func_76609_d(j1, k1 - 1, i2, var12);
				this.func_76609_d(j1, k1 + 1, i2, var12);
				this.func_76609_d(j1, k1, i2, var12);
			}

			this.field_76643_l = true;
		}
	}

	//getBlockLightOpacity
	public int func_76596_b(int par1, int par2, int par3) {
		int x = (this.field_76635_g << 4) + par1;
		int z = (this.field_76647_h << 4) + par3;
		Block block = Block.field_71973_m[this.func_76610_a(par1, par2, par3)];
		return block == null ? 0 : block.getLightOpacity(this.field_76637_e, x, par2, z);
	}

	//getBlockID
	public int func_76610_a(int par1, int par2, int par3) {
		ExtendedBlockStorage e = this.dhSectionAt(par2);
		return e != null ? e.func_76656_a(par1, par2 & 15, par3) : 0;
	}

	//getBlockMetadata
	public int func_76628_c(int par1, int par2, int par3) {
		ExtendedBlockStorage e = this.dhSectionAt(par2);
		return e != null ? e.func_76665_b(par1, par2 & 15, par3) : 0;
	}

	//setBlockIDWithMetadata
	public boolean func_76592_a(int par1, int par2, int par3, int par4, int par5) {
		if (par2 < DH_MIN_Y || par2 >= DH_MAX_Y) {
			return false;
		}
		int j1 = par3 << 4 | par1;
		if (par2 >= this.field_76638_b[j1] - 1) {
			this.field_76638_b[j1] = -999;
		}

		int k1 = this.field_76634_f[j1];
		int l1 = this.func_76610_a(par1, par2, par3);
		int i2 = this.func_76628_c(par1, par2, par3);
		if (l1 == par4 && i2 == par5) {
			return false;
		} else {
			ExtendedBlockStorage extendedblockstorage = this.dhSectionAt(par2);
			boolean flag = false;
			if (extendedblockstorage == null) {
				if (par4 == 0) {
					return false;
				}

				extendedblockstorage = this.dhSectionOrCreate(par2);
				if (extendedblockstorage == null) {
					return false;
				}
				flag = par2 >= k1;
			}

			int j2 = this.field_76635_g * 16 + par1;
			int k2 = this.field_76647_h * 16 + par3;
			if (l1 != 0 && !this.field_76637_e.field_72995_K) {
				Block.field_71973_m[l1].func_71927_h(this.field_76637_e, j2, par2, k2, i2);
			}

			extendedblockstorage.func_76655_a(par1, par2 & 15, par3, par4);
			TileEntity tileentity;
			if (l1 != 0) {
				if (!this.field_76637_e.field_72995_K) {
					Block.field_71973_m[l1].func_71852_a(this.field_76637_e, j2, par2, k2, l1, i2);
				} else if (Block.field_71973_m[l1] != null && Block.field_71973_m[l1].hasTileEntity(i2)) {
					tileentity = this.getChunkBlockTileEntityUnsafe(j2 & 15, par2, k2 & 15);
					if (tileentity != null && tileentity.shouldRefresh(l1, par4, i2, par5, this.field_76637_e, j2, par2, k2)) {
						this.field_76637_e.func_72932_q(j2, par2, k2);
					}
				}
			}

			if (extendedblockstorage.func_76656_a(par1, par2 & 15, par3) != par4) {
				return false;
			} else {
				extendedblockstorage.func_76654_b(par1, par2 & 15, par3, par5);
				if (flag) {
					this.func_76603_b();
				} else {
					if (this.func_76596_b(par1, par2, par3) > 0) {
						if (par2 >= k1) {
							this.func_76615_h(par1, par2 + 1, par3);
						}
					} else if (par2 == k1 - 1) {
						this.func_76615_h(par1, par2, par3);
					}

					this.func_76595_e(par1, par3);
				}

				if (par4 != 0) {
					if (!this.field_76637_e.field_72995_K && (!this.field_76637_e.captureBlockStates || Block.field_71973_m[par4].hasTileEntity(par5))) {
						Block.field_71973_m[par4].func_71861_g(this.field_76637_e, j2, par2, k2);
					}

					if (Block.field_71973_m[par4] != null && Block.field_71973_m[par4].hasTileEntity(par5)) {
						tileentity = this.func_76597_e(par1, par2, par3);
						if (tileentity == null) {
							tileentity = Block.field_71973_m[par4].createTileEntity(this.field_76637_e, par5);
							this.field_76637_e.func_72837_a(j2, par2, k2, tileentity);
						}

						if (tileentity != null) {
							tileentity.func_70321_h();
							tileentity.field_70325_p = par5;
						}
					}
				} else if (l1 > 0 && Block.field_71973_m[l1] instanceof ITileEntityProvider) {
					tileentity = this.func_76597_e(par1, par2, par3);
					if (tileentity != null) {
						tileentity.func_70321_h();
					}
				}

				this.field_76643_l = true;
				return true;
			}
		}
	}

	//setBlockMetadata
	public boolean func_76589_b(int par1, int par2, int par3, int par4) {
		ExtendedBlockStorage extendedblockstorage = this.dhSectionAt(par2);
		if (extendedblockstorage == null) {
			return false;
		} else {
			int i1 = extendedblockstorage.func_76665_b(par1, par2 & 15, par3);
			if (i1 == par4) {
				return false;
			} else {
				this.field_76643_l = true;
				extendedblockstorage.func_76654_b(par1, par2 & 15, par3, par4);
				int j1 = extendedblockstorage.func_76656_a(par1, par2 & 15, par3);
				if (j1 > 0 && Block.field_71973_m[j1] != null && Block.field_71973_m[j1].hasTileEntity(par4)) {
					TileEntity tileentity = this.func_76597_e(par1, par2, par3);
					if (tileentity != null) {
						tileentity.func_70321_h();
						tileentity.field_70325_p = par4;
					}
				}

				return true;
			}
		}
	}

	//getSavedLightValue
	public int func_76614_a(EnumSkyBlock par1EnumSkyBlock, int par2, int par3, int par4) {
		ExtendedBlockStorage e = this.dhSectionAt(par3);
		return e == null ? (this.func_76619_d(par2, par3, par4) ? par1EnumSkyBlock.field_77198_c : 0)
				: (par1EnumSkyBlock == EnumSkyBlock.Sky ? (this.field_76637_e.field_73011_w.field_76576_e ? 0 : e.func_76670_c(par2, par3 & 15, par4))
				: (par1EnumSkyBlock == EnumSkyBlock.Block ? e.func_76674_d(par2, par3 & 15, par4) : par1EnumSkyBlock.field_77198_c));
	}

	//setLightValue
	public void func_76633_a(EnumSkyBlock par1EnumSkyBlock, int par2, int par3, int par4, int par5) {
		ExtendedBlockStorage e = this.dhSectionAt(par3);
		if (e == null) {
			e = this.dhSectionOrCreate(par3);
			if (e == null) {
				return;
			}
			this.func_76603_b();
		}

		this.field_76643_l = true;
		if (par1EnumSkyBlock == EnumSkyBlock.Sky) {
			if (!this.field_76637_e.field_73011_w.field_76576_e) {
				e.func_76657_c(par2, par3 & 15, par4, par5);
			}
		} else if (par1EnumSkyBlock == EnumSkyBlock.Block) {
			e.func_76677_d(par2, par3 & 15, par4, par5);
		}
	}

	//getBlockLightValue
	public int func_76629_c(int par1, int par2, int par3, int par4) {
		ExtendedBlockStorage e = this.dhSectionAt(par2);
		if (e != null) {
			int i1 = this.field_76637_e.field_73011_w.field_76576_e ? 0 : e.func_76670_c(par1, par2 & 15, par3);
			if (i1 > 0) {
				field_76640_a = true;
			}

			i1 -= par4;
			int j1 = e.func_76674_d(par1, par2 & 15, par3);
			if (j1 > i1) {
				i1 = j1;
			}

			return i1;
		} else {
			return !this.field_76637_e.field_73011_w.field_76576_e && par4 < EnumSkyBlock.Sky.field_77198_c ? EnumSkyBlock.Sky.field_77198_c - par4 : 0;
		}
	}

	//addEntity
	public void func_76612_a(Entity par1Entity) {
		this.field_76644_m = true;
		int i = MathHelper.func_76128_c(par1Entity.field_70165_t / 16.0D);
		int j = MathHelper.func_76128_c(par1Entity.field_70161_v / 16.0D);
		if (i != this.field_76635_g || j != this.field_76647_h) {
			Bukkit.getLogger().warning("Wrong location for " + par1Entity + " in world '" + this.field_76637_e.getWorld().getName() + "'!");
			Bukkit.getLogger().warning("Entity is at " + par1Entity.field_70165_t + "," + par1Entity.field_70161_v + " (chunk " + i + "," + j + ") but was stored in chunk " + this.field_76635_g + "," + this.field_76647_h);
		}

		//chunkCoordY = section Y (-4..19)
		int sy = MathHelper.func_76128_c(par1Entity.field_70163_u / 16.0D);
		int k = dhEntityListIndex(sy, this.field_76645_j.length);

		MinecraftForge.EVENT_BUS.post(new EnteringChunk(par1Entity, this.field_76635_g, this.field_76647_h, par1Entity.field_70176_ah, par1Entity.field_70164_aj));
		par1Entity.field_70175_ag = true;
		par1Entity.field_70176_ah = this.field_76635_g;
		par1Entity.field_70162_ai = k - DH_OFFSET;
		par1Entity.field_70164_aj = this.field_76647_h;
		this.field_76645_j[k].add(par1Entity);
		if (par1Entity instanceof EntityLiving) {
			EntityLiving living = (EntityLiving) par1Entity;
			if (living.func_70692_ba_CodeFix_Public() && living.func_104002_bU()) {
				return;
			}
		}

		EnumCreatureType[] types = EnumCreatureType.values();
		for (int n = 0; n < types.length; ++n) {
			EnumCreatureType creatureType = types[n];
			if (creatureType.func_75598_a().isAssignableFrom(par1Entity.getClass())) {
				this.entityCount.adjustOrPutValue(creatureType.func_75598_a(), 1, 1);
			}
		}
	}

	//removeEntity
	public void func_76622_b(Entity par1Entity) {
		this.func_76608_a(par1Entity, par1Entity.field_70162_ai);
	}

	//removeEntityAtIndex
	public void func_76608_a(Entity par1Entity, int par2) {
		int k = dhEntityListIndex(par2, this.field_76645_j.length);
		if (!this.field_76645_j[k].remove(par1Entity)) {
			//Entité rangée ailleurs par du code legacy
			for (int n = 0; n < this.field_76645_j.length; ++n) {
				if (n != k && this.field_76645_j[n].remove(par1Entity)) {
					break;
				}
			}
		}
		if (par1Entity instanceof EntityLiving) {
			EntityLiving living = (EntityLiving) par1Entity;
			if (living.func_70692_ba_CodeFix_Public() && living.func_104002_bU()) {
				return;
			}
		}

		EnumCreatureType[] types = EnumCreatureType.values();
		for (int n = 0; n < types.length; ++n) {
			EnumCreatureType creatureType = types[n];
			if (creatureType.func_75598_a().isAssignableFrom(par1Entity.getClass())) {
				this.entityCount.adjustOrPutValue(creatureType.func_75598_a(), 1, -1);
			}
		}
	}

	//canBlockSeeTheSky
	public boolean func_76619_d(int par1, int par2, int par3) {
		return par2 >= this.field_76634_f[par3 << 4 | par1];
	}

	//getChunkBlockTileEntity
	public TileEntity func_76597_e(int par1, int par2, int par3) {
		ChunkPosition chunkposition = new ChunkPosition(par1, par2, par3);
		TileEntity tileentity = (TileEntity) this.field_76648_i.get(chunkposition);
		if (tileentity != null && tileentity.func_70320_p()) {
			this.field_76648_i.remove(chunkposition);
			tileentity = null;
		}

		if (tileentity == null) {
			int l = this.func_76610_a(par1, par2, par3);
			int meta = this.func_76628_c(par1, par2, par3);
			if (l <= 0 || !Block.field_71973_m[l].hasTileEntity(meta)) {
				return null;
			}

			tileentity = Block.field_71973_m[l].createTileEntity(this.field_76637_e, meta);
			this.field_76637_e.func_72837_a(this.field_76635_g * 16 + par1, par2, this.field_76647_h * 16 + par3, tileentity);
			tileentity = (TileEntity) this.field_76648_i.get(chunkposition);
		}

		return tileentity;
	}

	//addTileEntity
	public void func_76620_a(TileEntity par1TileEntity) {
		int i = par1TileEntity.field_70329_l - this.field_76635_g * 16;
		int j = par1TileEntity.field_70330_m;
		int k = par1TileEntity.field_70327_n - this.field_76647_h * 16;
		this.func_76604_a(i, j, k, par1TileEntity);
		if (this.field_76636_d) {
			this.field_76637_e.addTileEntity(par1TileEntity);
		}
	}

	//setChunkBlockTileEntity
	public void func_76604_a(int par1, int par2, int par3, TileEntity par4TileEntity) {
		ChunkPosition chunkposition = new ChunkPosition(par1, par2, par3);
		par4TileEntity.func_70308_a(this.field_76637_e);
		par4TileEntity.field_70329_l = this.field_76635_g * 16 + par1;
		par4TileEntity.field_70330_m = par2;
		par4TileEntity.field_70327_n = this.field_76647_h * 16 + par3;
		Block block = Block.field_71973_m[this.func_76610_a(par1, par2, par3)];
		if (block != null && block.hasTileEntity(this.func_76628_c(par1, par2, par3))) {
			if (this.field_76648_i.containsKey(chunkposition)) {
				((TileEntity) this.field_76648_i.get(chunkposition)).func_70313_j();
			}

			par4TileEntity.func_70312_q();
			this.field_76648_i.put(chunkposition, par4TileEntity);
		} else {
			if (!Setting.tileEntityPlaceLogging.getValue().booleanValue()) {
				return;
			}

			System.out.println("Attempted to place a tile entity (" + par4TileEntity + ") at " + par4TileEntity.field_70329_l + "," + par4TileEntity.field_70330_m + "," + par4TileEntity.field_70327_n + " (" + Material.getMaterial(this.func_76610_a(par1, par2, par3)) + ") where there was no entity tile!");
			System.out.println("Chunk coordinates: " + this.field_76635_g * 16 + "," + this.field_76647_h * 16);
			(new Exception()).printStackTrace();
		}
	}

	//removeChunkBlockTileEntity
	public void func_76627_f(int par1, int par2, int par3) {
		ChunkPosition chunkposition = new ChunkPosition(par1, par2, par3);
		if (this.field_76636_d) {
			TileEntity tileentity = (TileEntity) this.field_76648_i.remove(chunkposition);
			if (tileentity != null) {
				tileentity.func_70313_j();
			}
		}
	}

	//onChunkLoad
	public void func_76631_c() {
		this.field_76636_d = true;
		this.field_76637_e.func_72852_a(this.field_76648_i.values());

		for (int i = 0; i < this.field_76645_j.length; ++i) {
			Iterator iterator = this.field_76645_j[i].iterator();

			while (iterator.hasNext()) {
				Entity entity = (Entity) iterator.next();
				entity.func_110123_P();
			}

			this.field_76637_e.func_72868_a(this.field_76645_j[i]);
		}

		MinecraftForge.EVENT_BUS.post(new Load(this));
	}

	//onChunkUnload
	public void func_76623_d() {
		this.field_76636_d = false;
		Iterator iterator = this.field_76648_i.values().iterator();

		while (iterator.hasNext()) {
			TileEntity te = (TileEntity) iterator.next();
			this.field_76637_e.func_72928_a(te);
		}

		for (int n = 0; n < this.field_76645_j.length; ++n) {
			Iterator iter = this.field_76645_j[n].iterator();

			while (iter.hasNext()) {
				Entity entity = (Entity) iter.next();
				if (entity instanceof EntityPlayerMP) {
					iter.remove();
				}
			}

			this.field_76637_e.func_72828_b(this.field_76645_j[n]);
		}

		MinecraftForge.EVENT_BUS.post(new Unload(this));
	}

	public void func_76630_e() {
		this.field_76643_l = true;
	}

	//getEntitiesWithinAABBForEntity
	public void func_76588_a(Entity par1Entity, AxisAlignedBB par2AxisAlignedBB, List par3List, IEntitySelector par4IEntitySelector) {
		int len = this.field_76645_j.length;
		int i = MathHelper.func_76128_c((par2AxisAlignedBB.field_72338_b - World.MAX_ENTITY_RADIUS) / 16.0D) + DH_OFFSET;
		int j = MathHelper.func_76128_c((par2AxisAlignedBB.field_72337_e + World.MAX_ENTITY_RADIUS) / 16.0D) + DH_OFFSET;
		if (i < 0) {
			i = 0;
			j = Math.max(i, j);
		}

		if (j >= len) {
			j = len - 1;
			i = Math.min(i, j);
		}

		for (int k = i; k <= j; ++k) {
			List list1 = this.field_76645_j[k];

			for (int l = 0; l < list1.size(); ++l) {
				Entity entity1 = (Entity) list1.get(l);
				if (entity1 != par1Entity && entity1.field_70121_D.func_72326_a(par2AxisAlignedBB) && (par4IEntitySelector == null || par4IEntitySelector.func_82704_a(entity1))) {
					par3List.add(entity1);
					Entity[] parts = entity1.func_70021_al();
					if (parts != null) {
						for (int i1 = 0; i1 < parts.length; ++i1) {
							entity1 = parts[i1];
							if (entity1 != par1Entity && entity1.field_70121_D.func_72326_a(par2AxisAlignedBB) && (par4IEntitySelector == null || par4IEntitySelector.func_82704_a(entity1))) {
								par3List.add(entity1);
							}
						}
					}
				}
			}
		}
	}

	//getEntitiesOfTypeWithinAAAB
	public void func_76618_a(Class par1Class, AxisAlignedBB par2AxisAlignedBB, List par3List, IEntitySelector par4IEntitySelector) {
		int len = this.field_76645_j.length;
		int i = MathHelper.func_76128_c((par2AxisAlignedBB.field_72338_b - World.MAX_ENTITY_RADIUS) / 16.0D) + DH_OFFSET;
		int j = MathHelper.func_76128_c((par2AxisAlignedBB.field_72337_e + World.MAX_ENTITY_RADIUS) / 16.0D) + DH_OFFSET;
		if (i < 0) {
			i = 0;
		} else if (i >= len) {
			i = len - 1;
		}

		if (j >= len) {
			j = len - 1;
		} else if (j < 0) {
			j = 0;
		}

		for (int k = i; k <= j; ++k) {
			List list1 = this.field_76645_j[k];

			for (int l = 0; l < list1.size(); ++l) {
				Entity entity = (Entity) list1.get(l);
				if (par1Class.isAssignableFrom(entity.getClass()) && entity.field_70121_D.func_72326_a(par2AxisAlignedBB) && (par4IEntitySelector == null || par4IEntitySelector.func_82704_a(entity))) {
					par3List.add(entity);
				}
			}
		}
	}

	//needsSaving
	public boolean func_76601_a(boolean par1) {
		if (par1) {
			if (this.field_76644_m && this.field_76637_e.func_82737_E() != this.field_76641_n || this.field_76643_l) {
				return true;
			}
		} else if (this.field_76644_m && this.field_76637_e.func_82737_E() >= this.field_76641_n + 600L) {
			return true;
		}

		return this.field_76643_l;
	}

	public Random func_76617_a(long par1) {
		return new Random(this.field_76637_e.func_72905_C() + (long) (this.field_76635_g * this.field_76635_g * 4987142) + (long) (this.field_76635_g * 5947611) + (long) (this.field_76647_h * this.field_76647_h) * 4392871L + (long) (this.field_76647_h * 389711) ^ par1);
	}

	public boolean func_76621_g() {
		return false;
	}

	//populateChunk
	public void func_76624_a(IChunkProvider par1IChunkProvider, IChunkProvider par2IChunkProvider, int par3, int par4) {
		if (!this.field_76646_k && par1IChunkProvider.func_73149_a(par3 + 1, par4 + 1) && par1IChunkProvider.func_73149_a(par3, par4 + 1) && par1IChunkProvider.func_73149_a(par3 + 1, par4)) {
			par1IChunkProvider.func_73153_a(par2IChunkProvider, par3, par4);
		}

		if (par1IChunkProvider.func_73149_a(par3 - 1, par4) && !par1IChunkProvider.func_73154_d(par3 - 1, par4).field_76646_k && par1IChunkProvider.func_73149_a(par3 - 1, par4 + 1) && par1IChunkProvider.func_73149_a(par3, par4 + 1) && par1IChunkProvider.func_73149_a(par3 - 1, par4 + 1)) {
			par1IChunkProvider.func_73153_a(par2IChunkProvider, par3 - 1, par4);
		}

		if (par1IChunkProvider.func_73149_a(par3, par4 - 1) && !par1IChunkProvider.func_73154_d(par3, par4 - 1).field_76646_k && par1IChunkProvider.func_73149_a(par3 + 1, par4 - 1) && par1IChunkProvider.func_73149_a(par3 + 1, par4 - 1) && par1IChunkProvider.func_73149_a(par3 + 1, par4)) {
			par1IChunkProvider.func_73153_a(par2IChunkProvider, par3, par4 - 1);
		}

		if (par1IChunkProvider.func_73149_a(par3 - 1, par4 - 1) && !par1IChunkProvider.func_73154_d(par3 - 1, par4 - 1).field_76646_k && par1IChunkProvider.func_73149_a(par3, par4 - 1) && par1IChunkProvider.func_73149_a(par3 - 1, par4)) {
			par1IChunkProvider.func_73153_a(par2IChunkProvider, par3 - 1, par4 - 1);
		}
	}

	//getPrecipitationHeight
	public int func_76626_d(int par1, int par2) {
		int k = par1 | par2 << 4;
		int l = this.field_76638_b[k];
		if (l == -999) {
			int i1 = this.func_76625_h() + 15;
			l = DH_MIN_Y;
			boolean found = false;

			while (i1 > DH_MIN_Y && !found) {
				int j1 = this.func_76610_a(par1, i1, par2);
				net.minecraft.block.material.Material material = j1 != 0 && Block.field_71973_m[j1] != null ? Block.field_71973_m[j1].field_72018_cp : net.minecraft.block.material.Material.field_76249_a;
				if (!material.func_76230_c() && !material.func_76224_d()) {
					--i1;
				} else {
					l = i1 + 1;
					found = true;
				}
			}

			this.field_76638_b[k] = l;
		}

		return l;
	}

	//updateSkylight
	public void func_76586_k() {
		if (this.field_76650_s && !this.field_76637_e.field_73011_w.field_76576_e) {
			this.func_76593_q();
		}
	}

	public ChunkCoordIntPair func_76632_l() {
		return new ChunkCoordIntPair(this.field_76635_g, this.field_76647_h);
	}

	//getAreLevelsEmpty
	public boolean func_76606_c(int par1, int par2) {
		if (par1 < DH_MIN_Y) {
			par1 = DH_MIN_Y;
		}

		if (par2 >= DH_MAX_Y) {
			par2 = DH_MAX_Y - 1;
		}

		for (int k = par1; k <= par2; k += 16) {
			ExtendedBlockStorage e = this.dhSectionAt(k);
			if (e != null && !e.func_76663_a()) {
				return false;
			}
		}

		return true;
	}

	//setStorageArrays
	public void func_76602_a(ExtendedBlockStorage[] par1ArrayOfExtendedBlockStorage) {
		this.dhLegacyPending = null;
		this.field_76652_q = dhNormalize(par1ArrayOfExtendedBlockStorage);
	}

	public BiomeGenBase func_76591_a(int par1, int par2, WorldChunkManager par3WorldChunkManager) {
		int k = this.field_76651_r[par2 << 4 | par1] & 255;
		if (k == 255) {
			BiomeGenBase biomegenbase = par3WorldChunkManager.func_76935_a((this.field_76635_g << 4) + par1, (this.field_76647_h << 4) + par2);
			k = biomegenbase.field_76756_M;
			this.field_76651_r[par2 << 4 | par1] = (byte) (k & 255);
		}

		return BiomeGenBase.field_76773_a[k] == null ? BiomeGenBase.field_76772_c : BiomeGenBase.field_76773_a[k];
	}

	public byte[] func_76605_m() {
		return this.field_76651_r;
	}

	public void func_76616_a(byte[] par1ArrayOfByte) {
		this.field_76651_r = par1ArrayOfByte;
	}

	//resetRelightChecks
	public void func_76613_n() {
		this.field_76649_t = 0;
	}

	//enqueueRelightChecks : sections hors 0..255 vérifiées seulement si utilisées
	public void func_76594_o() {
		int done = 0;
		while (done < 8) {
			if (this.field_76649_t >= DH_RELIGHT_TOTAL) {
				return;
			}

			int j = this.field_76649_t % DH_SECTIONS;
			int k = this.field_76649_t / DH_SECTIONS % 16;
			int l = this.field_76649_t / (DH_SECTIONS * 16);
			++this.field_76649_t;
			if (!this.dhRelightSection(j)) {
				continue;
			}
			++done;
			int i1 = (this.field_76635_g << 4) + k;
			int j1 = (this.field_76647_h << 4) + l;
			ExtendedBlockStorage sec = j < this.field_76652_q.length ? this.field_76652_q[j] : null;

			for (int k1 = 0; k1 < 16; ++k1) {
				int l1 = (j << 4) + DH_MIN_Y + k1;
				if (sec == null && (k1 == 0 || k1 == 15 || k == 0 || k == 15 || l == 0 || l == 15) || sec != null && sec.func_76656_a(k, k1, l) == 0) {
					if (Block.field_71984_q[this.field_76637_e.func_72798_a(i1, l1 - 1, j1)] > 0) {
						this.field_76637_e.func_72969_x(i1, l1 - 1, j1);
					}

					if (Block.field_71984_q[this.field_76637_e.func_72798_a(i1, l1 + 1, j1)] > 0) {
						this.field_76637_e.func_72969_x(i1, l1 + 1, j1);
					}

					if (Block.field_71984_q[this.field_76637_e.func_72798_a(i1 - 1, l1, j1)] > 0) {
						this.field_76637_e.func_72969_x(i1 - 1, l1, j1);
					}

					if (Block.field_71984_q[this.field_76637_e.func_72798_a(i1 + 1, l1, j1)] > 0) {
						this.field_76637_e.func_72969_x(i1 + 1, l1, j1);
					}

					if (Block.field_71984_q[this.field_76637_e.func_72798_a(i1, l1, j1 - 1)] > 0) {
						this.field_76637_e.func_72969_x(i1, l1, j1 - 1);
					}

					if (Block.field_71984_q[this.field_76637_e.func_72798_a(i1, l1, j1 + 1)] > 0) {
						this.field_76637_e.func_72969_x(i1, l1, j1 + 1);
					}

					this.field_76637_e.func_72969_x(i1, l1, j1);
				}
			}
		}
	}

	private boolean dhRelightSection(int j) {
		if (j >= DH_OFFSET && j < DH_OFFSET + DH_LEGACY_SECTIONS) {
			return true;
		}
		ExtendedBlockStorage[] s = this.field_76652_q;
		return j < s.length && s[j] != null
				|| j > 0 && j - 1 < s.length && s[j - 1] != null
				|| j + 1 < s.length && s[j + 1] != null;
	}

	public void cleanChunkBlockTileEntity(int x, int y, int z) {
		ChunkPosition position = new ChunkPosition(x, y, z);
		if (this.field_76636_d) {
			TileEntity entity = (TileEntity) this.field_76648_i.get(position);
			if (entity != null && entity.func_70320_p()) {
				this.field_76648_i.remove(position);
			}
		}
	}

	public TileEntity getChunkBlockTileEntityUnsafe(int x, int y, int z) {
		ChunkPosition chunkposition = new ChunkPosition(x, y, z);
		TileEntity tileentity = (TileEntity) this.field_76648_i.get(chunkposition);
		if (tileentity != null && tileentity.func_70320_p()) {
			this.field_76648_i.remove(chunkposition);
			tileentity = null;
		}

		return tileentity;
	}
}
