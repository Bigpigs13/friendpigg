package com.friendping;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Локальный список друзей (ники), хранится в config/friendping_friends.txt */
public class FriendList {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("friendping_friends.txt");
    private static final List<String> NAMES = new ArrayList<>();

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                for (String s : Files.readAllLines(FILE)) {
                    s = s.trim();
                    if (!s.isEmpty()) NAMES.add(s);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void save() {
        try { Files.write(FILE, NAMES); } catch (Exception ignored) {}
    }

    public static boolean add(String name) {
        if (has(name)) return false;
        NAMES.add(name);
        save();
        return true;
    }

    public static boolean remove(String name) {
        boolean r = NAMES.removeIf(n -> n.equalsIgnoreCase(name));
        if (r) save();
        return r;
    }

    public static boolean has(String name) {
        return NAMES.stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }

    public static List<String> all() { return List.copyOf(NAMES); }

    /** Ищет ник друга в тексте сообщения. */
    public static String findIn(String text) {
        String low = text.toLowerCase(Locale.ROOT);
        for (String n : NAMES) {
            if (low.contains(n.toLowerCase(Locale.ROOT))) return n;
        }
        return null;
    }
}
