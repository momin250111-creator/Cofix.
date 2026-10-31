package com.cofix;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class CoFixPlugin extends JavaPlugin {
    private Path snapshotPath;
    private Path buildingPath;
    private BukkitTask activeTask;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        snapshotPath = getDataFolder().toPath().resolve("snapshot");
        buildingPath = getDataFolder().toPath().resolve("snapshot-building");
        getLogger().info("CoFix enabled. Snapshots are stored in " + snapshotPath);
    }

    @Override
    public void onDisable() {
        if (activeTask != null) activeTask.cancel();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("cofix.admin")) {
            sender.sendMessage("§cYou don't have permission to use CoFix.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("save")) {
            beginSave(sender);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            showStatus(sender);
            return true;
        }
        if (args.length == 0) {
            beginRestore(sender, null);
            return true;
        }
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cA radius restore must be run by a player.");
                return true;
            }
            try {
                int radius = Integer.parseInt(args[0]);
                if (radius < 1 || radius > 10000) throw new NumberFormatException();
                beginRestore(sender, new RestoreCenter(player.getWorld().getUID(),
                        player.getLocation().getBlockX(), player.getLocation().getBlockY(),
                        player.getLocation().getBlockZ(), radius));
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cUsage: /cofix [save|status|radius]");
            }
            return true;
        }
        sender.sendMessage("§eCoFix commands:");
        sender.sendMessage("§f/cofix save §7- save all generated chunks in all loaded worlds");
        sender.sendMessage("§f/cofix §7- restore the full saved snapshot");
        sender.sendMessage("§f/cofix <radius> §7- restore within that block radius around you");
        sender.sendMessage("§f/cofix status §7- show snapshot status");
        return true;
    }

    private void beginSave(CommandSender sender) {
        if (!startBusy(sender)) return;
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            busy.set(false);
            sender.sendMessage("§cFor a consistent full-world snapshot, stop players from editing first.");
            sender.sendMessage("§7Kick/whitelist players or run this during maintenance, then use /cofix save again.");
            return;
        }
        try {
            deleteTree(buildingPath);
            Files.createDirectories(buildingPath);
            List<ChunkRef> chunks = discoverGeneratedChunks();
            if (chunks.isEmpty()) {
                busy.set(false);
                sender.sendMessage("§cNo generated region chunks were found.");
                return;
            }
            sender.sendMessage("§aCoFix snapshot started: §f" + chunks.size() + "§a chunks across loaded worlds.");
            int perTick = Math.max(1, getConfig().getInt("chunks-per-tick", 1));
            int[] index = {0};
            activeTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    for (int n = 0; n < perTick && index[0] < chunks.size(); n++) {
                        ChunkRef ref = chunks.get(index[0]++);
                        World world = Bukkit.getWorld(ref.worldId());
                        if (world == null) continue;
                        saveChunk(world, ref.x(), ref.z());
                    }
                    if (index[0] >= chunks.size()) {
                        activeTask.cancel();
                        replaceSnapshot();
                        busy.set(false);
                        sender.sendMessage("§aCoFix snapshot saved successfully. §f" + chunks.size() + "§a chunks captured.");
                    } else if (index[0] % 100 == 0) {
                        sender.sendMessage("§7CoFix snapshot progress: " + index[0] + "/" + chunks.size());
                    }
                } catch (Exception ex) {
                    activeTask.cancel();
                    busy.set(false);
                    getLogger().severe("Snapshot failed: " + ex.getMessage());
                    sender.sendMessage("§cSnapshot failed: " + ex.getMessage());
                }
            }, 1L, 1L);
        } catch (Exception ex) {
            busy.set(false);
            sender.sendMessage("§cCould not start snapshot: " + ex.getMessage());
            getLogger().severe("Could not start snapshot: " + ex.getMessage());
        }
    }

    private List<ChunkRef> discoverGeneratedChunks() throws IOException {
        List<ChunkRef> result = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            Path regionDir = world.getWorldFolder().toPath().resolve("region");
            if (!Files.isDirectory(regionDir)) continue;
            try (var files = Files.list(regionDir)) {
                for (Path file : files.filter(p -> p.getFileName().toString().matches("r.-?\\d+.-?\\d+\\.mca")).toList()) {
                    String name = file.getFileName().toString();
                    String[] parts = name.substring(2, name.length() - 4).split("\\.");
                    int regionX = Integer.parseInt(parts[0]);
                    int regionZ = Integer.parseInt(parts[1]);
                    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                        if (raf.length() < 4096) continue;
                        for (int slot = 0; slot < 1024; slot++) {
                            raf.seek(slot * 4L);
                            if (raf.readInt() != 0) {
                                int cx = regionX * 32 + (slot % 32);
                                int cz = regionZ * 32 + (slot / 32);
                                result.add(new ChunkRef(world.getUID(), cx, cz));
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private void saveChunk(World world, int cx, int cz) throws Exception {
        Chunk chunk = world.getChunkAt(cx, cz);
        Path worldDir = buildingPath.resolve(world.getUID().toString());
        Files.createDirectories(worldDir);
        Path file = worldDir.resolve("c_" + cx + "_" + cz + ".dat");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))))) {
            out.writeInt(1);
            out.writeInt(cx);
            out.writeInt(cz);
            out.writeInt(world.getMinHeight());
            out.writeInt(world.getMaxHeight());
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        out.writeUTF(chunk.getBlock(x, y, z).getBlockData().getAsString());
                    }
                }
            }
            List<BlockExtra> extras = new ArrayList<>();
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = chunk.getBlock(x, y, z).getState();
                        if (state instanceof Container container) {
                            extras.add(new BlockExtra(x, y, z, serializeItems(container.getInventory().getContents()), null));
                        } else if (state instanceof Sign sign) {
                            extras.add(new BlockExtra(x, y, z, null, sign.getLines()));
                        }
                    }
                }
            }
            out.writeInt(extras.size());
            for (BlockExtra extra : extras) {
                out.writeByte(extra.x());
                out.writeInt(extra.y());
                out.writeByte(extra.z());
                if (extra.items() != null) {
                    out.writeByte(1);
                    out.writeInt(extra.items().length);
                    out.write(extra.items());
                } else {
                    out.writeByte(2);
                    String[] lines = extra.lines();
                    out.writeInt(lines.length);
                    for (String line : lines) out.writeUTF(line == null ? "" : line);
                }
            }
        }
    }

    private byte[] serializeItems(ItemStack[] items) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(items);
        }
        return bytes.toByteArray();
    }

    private ItemStack[] deserializeItems(byte[] bytes) throws IOException, ClassNotFoundException {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (ItemStack[]) in.readObject();
        }
    }

    private void replaceSnapshot() throws IOException {
        Path old = getDataFolder().toPath().resolve("snapshot-old");
        deleteTree(old);
        if (Files.exists(snapshotPath)) Files.move(snapshotPath, old, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(buildingPath, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
            deleteTree(old);
        } catch (IOException ex) {
            if (Files.exists(old) && !Files.exists(snapshotPath)) Files.move(old, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
            throw ex;
        }
    }

    private void beginRestore(CommandSender sender, RestoreCenter center) {
        if (!startBusy(sender)) return;
        if (!Files.isDirectory(snapshotPath)) {
            busy.set(false);
            sender.sendMessage("§cNo snapshot exists. Run /cofix save first.");
            return;
        }
        try {
            List<Path> files = new ArrayList<>();
            try (var worldDirs = Files.list(snapshotPath)) {
                for (Path worldDir : worldDirs.filter(Files::isDirectory).toList()) {
                    if (center != null && !worldDir.getFileName().toString().equals(center.worldId().toString())) continue;
                    try (var chunkFiles = Files.list(worldDir)) {
                        files.addAll(chunkFiles.filter(p -> p.getFileName().toString().endsWith(".dat")).toList());
                    }
                }
            }
            if (files.isEmpty()) {
                busy.set(false);
                sender.sendMessage("§cNo saved chunks found for that world/area.");
                return;
            }
            sender.sendMessage("§eCoFix restore started for §f" + files.size() + "§e saved chunks.");
            int perTick = Math.max(1, getConfig().getInt("chunks-per-tick", 1));
            int[] index = {0};
            activeTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    for (int n = 0; n < perTick && index[0] < files.size(); n++) {
                        restoreChunkFile(files.get(index[0]++), center);
                    }
                    if (index[0] >= files.size()) {
                        activeTask.cancel();
                        busy.set(false);
                        sender.sendMessage("§aCoFix restore finished. §f" + files.size() + "§a snapshot chunks processed.");
                    } else if (index[0] % 100 == 0) {
                        sender.sendMessage("§7CoFix restore progress: " + index[0] + "/" + files.size());
                    }
                } catch (Exception ex) {
                    activeTask.cancel();
                    busy.set(false);
                    getLogger().severe("Restore failed: " + ex.getMessage());
                    sender.sendMessage("§cRestore failed: " + ex.getMessage());
                }
            }, 1L, 1L);
        } catch (Exception ex) {
            busy.set(false);
            sender.sendMessage("§cCould not start restore: " + ex.getMessage());
        }
    }

    private void restoreChunkFile(Path file, RestoreCenter center) throws Exception {
        String worldId = file.getParent().getFileName().toString();
        World world = Bukkit.getWorld(UUID.fromString(worldId));
        if (world == null) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            int version = in.readInt();
            if (version != 1) throw new IOException("Unsupported snapshot version " + version);
            int cx = in.readInt();
            int cz = in.readInt();
            int minY = in.readInt();
            int maxY = in.readInt();
            Chunk chunk = world.getChunkAt(cx, cz);
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        String data = in.readUTF();
                        int bx = cx * 16 + x;
                        int bz = cz * 16 + z;
                        if (center == null || insideRadius(center, bx, y, bz)) {
                            chunk.getBlock(x, y, z).setBlockData(Bukkit.createBlockData(data), false);
                        }
                    }
                }
            }
            int extraCount = in.readInt();
            for (int i = 0; i < extraCount; i++) {
                int x = in.readUnsignedByte();
                int y = in.readInt();
                int z = in.readUnsignedByte();
                int type = in.readUnsignedByte();
                int bx = cx * 16 + x;
                int bz = cz * 16 + z;
                if (type == 1) {
                    int length = in.readInt();
                    byte[] bytes = in.readNBytes(length);
                    if (center == null || insideRadius(center, bx, y, bz)) {
                        BlockState state = chunk.getBlock(x, y, z).getState();
                        if (state instanceof Container container) {
                            container.getInventory().setContents(deserializeItems(bytes));
                            container.update(true, false);
                        }
                    }
                } else if (type == 2) {
                    int count = in.readInt();
                    String[] lines = new String[count];
                    for (int j = 0; j < count; j++) lines[j] = in.readUTF();
                    if (center == null || insideRadius(center, bx, y, bz)) {
                        BlockState state = chunk.getBlock(x, y, z).getState();
                        if (state instanceof Sign sign) {
                            for (int j = 0; j < Math.min(4, lines.length); j++) sign.setLine(j, lines[j]);
                            sign.update(true, false);
                        }
                    }
                }
            }
        }
    }

    private boolean insideRadius(RestoreCenter c, int x, int y, int z) {
        long dx = (long) x - c.x();
        long dy = (long) y - c.y();
        long dz = (long) z - c.z();
        long r = c.radius();
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    private boolean startBusy(CommandSender sender) {
        if (!busy.compareAndSet(false, true)) {
            sender.sendMessage("§cA CoFix operation is already running.");
            return false;
        }
        return true;
    }

    private void showStatus(CommandSender sender) {
        if (!Files.isDirectory(snapshotPath)) {
            sender.sendMessage("§cNo CoFix snapshot has been saved yet.");
            return;
        }
        try (var worlds = Files.list(snapshotPath)) {
            long worldCount = worlds.filter(Files::isDirectory).count();
            long chunkCount = 0;
            try (var dirs = Files.list(snapshotPath)) {
                for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                    try (var files = Files.list(dir)) {
                        chunkCount += files.filter(p -> p.getFileName().toString().endsWith(".dat")).count();
                    }
                }
            }
            sender.sendMessage("§aCoFix snapshot found.");
            sender.sendMessage("§7Worlds: §f" + worldCount + " §7| Chunks: §f" + chunkCount);
        } catch (IOException ex) {
            sender.sendMessage("§cCould not read snapshot status: " + ex.getMessage());
        }
    }

    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private record ChunkRef(UUID worldId, int x, int z) {}
    private record RestoreCenter(UUID worldId, int x, int y, int z, int radius) {}
    private record BlockExtra(int x, int y, int z, byte[] items, String[] lines) {}
}
