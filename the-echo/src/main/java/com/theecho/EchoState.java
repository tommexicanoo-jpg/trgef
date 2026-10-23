package com.theecho;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.*;

/** Estado persistente del mod (se guarda con el mundo). */
public class EchoState extends PersistentState {

    public static final class PlayerData {
        public final Set<String> day1Items = new HashSet<>();
        public final Set<String> offered = new HashSet<>();
        public boolean snapshotLocked = false;
        public double dread = 0;
        public long banishedUntil = 0;
        public boolean inRealm = false;
        public int slot = -1;
        public BlockPos returnPos = null;
        public String returnDim = null;
        public BlockPos realmBase = null;
        public BlockPos realmOriginal = null;
    }

    public record MirrorPos(String dim, BlockPos pos) {}

    public final Map<UUID, PlayerData> players = new HashMap<>();
    public final List<MirrorPos> mirrors = new ArrayList<>();
    public int nextSlot = 0;

    private static final PersistentState.Type<EchoState> TYPE =
            new PersistentState.Type<>(EchoState::new, EchoState::fromNbt, null);

    public static EchoState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "the_echo");
    }

    public PlayerData data(UUID id) {
        return players.computeIfAbsent(id, k -> new PlayerData());
    }

    public void reset(UUID id) {
        players.remove(id);
        markDirty();
    }

    private static void putPos(NbtCompound c, String key, BlockPos pos) {
        if (pos != null) c.putLong(key, pos.asLong());
    }

    private static BlockPos getPos(NbtCompound c, String key) {
        return c.contains(key) ? BlockPos.fromLong(c.getLong(key)) : null;
    }

    private static NbtList strings(Collection<String> col) {
        NbtList l = new NbtList();
        for (String s : col) l.add(NbtString.of(s));
        return l;
    }

    private static void readStrings(NbtCompound c, String key, Set<String> out) {
        NbtList l = c.getList(key, NbtElement.STRING_TYPE);
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.putInt("nextSlot", nextSlot);
        NbtList pl = new NbtList();
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            PlayerData d = e.getValue();
            NbtCompound c = new NbtCompound();
            c.putString("uuid", e.getKey().toString());
            c.putBoolean("locked", d.snapshotLocked);
            c.putDouble("dread", d.dread);
            c.putLong("banished", d.banishedUntil);
            c.putBoolean("inRealm", d.inRealm);
            c.putInt("slot", d.slot);
            c.put("day1", strings(d.day1Items));
            c.put("offered", strings(d.offered));
            putPos(c, "ret", d.returnPos);
            putPos(c, "base", d.realmBase);
            putPos(c, "orig", d.realmOriginal);
            if (d.returnDim != null) c.putString("retDim", d.returnDim);
            pl.add(c);
        }
        nbt.put("players", pl);

        NbtList ml = new NbtList();
        for (MirrorPos m : mirrors) {
            NbtCompound c = new NbtCompound();
            c.putString("dim", m.dim());
            c.putLong("pos", m.pos().asLong());
            ml.add(c);
        }
        nbt.put("mirrors", ml);
        return nbt;
    }

    public static EchoState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        EchoState s = new EchoState();
        s.nextSlot = nbt.getInt("nextSlot");
        NbtList pl = nbt.getList("players", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < pl.size(); i++) {
            NbtCompound c = pl.getCompound(i);
            PlayerData d = new PlayerData();
            d.snapshotLocked = c.getBoolean("locked");
            d.dread = c.getDouble("dread");
            d.banishedUntil = c.getLong("banished");
            d.inRealm = c.getBoolean("inRealm");
            d.slot = c.contains("slot") ? c.getInt("slot") : -1;
            readStrings(c, "day1", d.day1Items);
            readStrings(c, "offered", d.offered);
            d.returnPos = getPos(c, "ret");
            d.realmBase = getPos(c, "base");
            d.realmOriginal = getPos(c, "orig");
            d.returnDim = c.contains("retDim") ? c.getString("retDim") : null;
            s.players.put(UUID.fromString(c.getString("uuid")), d);
        }
        NbtList ml = nbt.getList("mirrors", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < ml.size(); i++) {
            NbtCompound c = ml.getCompound(i);
            s.mirrors.add(new MirrorPos(c.getString("dim"), BlockPos.fromLong(c.getLong("pos"))));
        }
        return s;
    }
}
