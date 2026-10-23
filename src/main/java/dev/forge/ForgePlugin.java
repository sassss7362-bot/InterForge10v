package dev.forge;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.common.collect.Multimap;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ForgePlugin extends JavaPlugin implements Listener {

    enum Menu { MAIN, LIST, EDITOR, ENCH, ATTR, EFFECT, LOOK, NAME, NCOLOR, NGRAD, NSTYLE, CENCH, PEFFECT, TNT, BOOKCAT, BOOKS }
    enum Cat { WEAPON, ARMOR, TOOL, SPHERE, TALISMAN, POTION, TNT }

    static class Holder implements InventoryHolder {
        final Menu menu; final int page; Inventory inv;
        Holder(Menu m, int p) { menu = m; page = p; }
        @Override public Inventory getInventory() { return inv; }
    }

    /** Налаштування назви в «Студії назв». cols: 1 колір = суцільний, 2+ = градієнт. */
    static class NameStyle {
        String text = "Предмет";
        List<String> cols = new ArrayList<>();
        boolean b, i, u, st, ob;
        int font = 0;   // 0 звичайний, 1 uniform, 2 alt (зачарування), 3 illager
        int uni = 0;    // стиль букв
    }

    static class Session {
        Cat cat; Material base; ItemStack item; String pendingKind; String pendingKey;
        NameStyle ns;
        int bookCat = 0;
        String colorTarget = "name";
        boolean colorMode = false;
    }

    /** Кастомне зачарування, що накладається книгою. */
    static class CE {
        final String id, name, desc; final Material icon; final int max; final String[] applies;
        CE(String id, String name, String desc, Material icon, int max, String[] applies) {
            this.id = id; this.name = name; this.desc = desc; this.icon = icon; this.max = max; this.applies = applies;
        }
        boolean applies(Material m) {
            String n = m.name();
            for (String a : applies) if (a.startsWith("_") ? n.endsWith(a) : n.equals(a)) return true;
            return false;
        }
    }

    static final String[] DIG = {"_PICKAXE", "_SHOVEL", "_AXE"};
    static final String[] MELEE = {"_SWORD", "_AXE", "_SPEAR", "MACE", "TRIDENT"};
    static final String[] TALI_ONLY = {"TOTEM_OF_UNDYING"};
    static final Map<String, CE> CES = new LinkedHashMap<>();
    static {
        CES.put("hammer", new CE("hammer", "Молот", "Ламає область углиб і вгору: I — 3x3x3, II — 10x10x10 (розміри в config.yml)", Material.IRON_PICKAXE, 2, DIG));
        CES.put("smelt", new CE("smelt", "Автоплавка", "Видобуток одразу переплавляється", Material.FURNACE, 1, DIG));
        CES.put("magnet", new CE("magnet", "Магніт", "Дроп летить прямо в інвентар", Material.LODESTONE, 1,
                new String[]{"_PICKAXE", "_SHOVEL", "_AXE", "_HOE"}));
        CES.put("vein", new CE("vein", "Жильник", "Копає всю жилу руди (з Shift)", Material.DIAMOND_ORE, 1, new String[]{"_PICKAXE"}));
        CES.put("timber", new CE("timber", "Лісоруб", "Зрубує все дерево одразу", Material.OAK_LOG, 1, new String[]{"_AXE"}));
        CES.put("vampire", new CE("vampire", "Вампіризм", "Лікує частину нанесеного урону", Material.GHAST_TEAR, 5, MELEE));
        CES.put("lightning", new CE("lightning", "Блискавка", "Шанс вдарити ціль блискавкою", Material.LIGHTNING_ROD, 5, MELEE));
        // талісман
        CES.put("guard", new CE("guard", "Захист", "−6% вхідної шкоди за рівень (хотбар або ліва рука)", Material.SHIELD, 5, TALI_ONLY));
        CES.put("reflect", new CE("reflect", "Шипи", "Повертає 10% шкоди нападнику за рівень", Material.CACTUS, 5, TALI_ONLY));
        CES.put("recover", new CE("recover", "Відновлення", "Лікує 0.5 серця кожні 2 с за рівень", Material.GLISTERING_MELON_SLICE, 5, TALI_ONLY));
    }

    // категорії книг: назва, зразок предмета
    private static final String[][] BCATS = {
            {"Меч", "DIAMOND_SWORD"}, {"Сокира", "DIAMOND_AXE"}, {"Кірка", "DIAMOND_PICKAXE"},
            {"Лопата", "DIAMOND_SHOVEL"}, {"Мотика", "DIAMOND_HOE"}, {"Лук", "BOW"},
            {"Арбалет", "CROSSBOW"}, {"Тризуб", "TRIDENT"}, {"Булава", "MACE"},
            {"Шолом", "DIAMOND_HELMET"}, {"Нагрудник", "DIAMOND_CHESTPLATE"}, {"Штани", "DIAMOND_LEGGINGS"},
            {"Чоботи", "DIAMOND_BOOTS"}, {"Елітри", "ELYTRA"}, {"Щит", "SHIELD"},
            {"Вудка", "FISHING_ROD"}, {"Талісман", "TOTEM_OF_UNDYING"},
    };

    // key, назва, іконка, крок
    private static final Object[][] ATTRS = {
            {"attack_damage", "Сила (додатковий урон)", Material.IRON_SWORD, 1.0},
            {"attack_speed", "Швидкість атаки", Material.CLOCK, 0.2},
            {"max_health", "Макс. здоров'я", Material.APPLE, 2.0},
            {"armor", "Броня", Material.IRON_CHESTPLATE, 1.0},
            {"armor_toughness", "Міцність броні", Material.NETHERITE_INGOT, 1.0},
            {"movement_speed", "Швидкість руху", Material.SUGAR, 0.02},
            {"knockback_resistance", "Опір відкиданню", Material.SHIELD, 0.1},
            {"luck", "Удача", Material.RABBIT_FOOT, 1.0},
    };

    private static final Object[][] EFFECTS = {
            {"strength", "Сила", Material.BLAZE_POWDER},
            {"speed", "Швидкість", Material.SUGAR},
            {"resistance", "Стійкість", Material.IRON_CHESTPLATE},
            {"regeneration", "Регенерація", Material.GHAST_TEAR},
            {"fire_resistance", "Вогнестійкість", Material.MAGMA_CREAM},
            {"jump_boost", "Стрибок", Material.RABBIT_FOOT},
            {"haste", "Поспішність", Material.GOLDEN_PICKAXE},
            {"night_vision", "Нічний зір", Material.GOLDEN_CARROT},
            {"water_breathing", "Підводне дихання", Material.PUFFERFISH},
            {"absorption", "Поглинання", Material.GOLDEN_APPLE},
            {"health_boost", "Додаткове здоров'я", Material.APPLE},
            {"invisibility", "Невидимість", Material.FERMENTED_SPIDER_EYE},
            {"slow_falling", "Повільне падіння", Material.FEATHER},
            {"luck", "Удача", Material.EMERALD},
    };

    // ефекти для зілля (є й негативні)
    private static final Object[][] PEFFECTS = {
            {"strength", "Сила", Material.BLAZE_POWDER},
            {"speed", "Швидкість", Material.SUGAR},
            {"resistance", "Стійкість", Material.IRON_CHESTPLATE},
            {"regeneration", "Регенерація", Material.GHAST_TEAR},
            {"fire_resistance", "Вогнестійкість", Material.MAGMA_CREAM},
            {"jump_boost", "Стрибок", Material.RABBIT_FOOT},
            {"haste", "Поспішність", Material.GOLDEN_PICKAXE},
            {"night_vision", "Нічний зір", Material.GOLDEN_CARROT},
            {"water_breathing", "Підводне дихання", Material.PUFFERFISH},
            {"absorption", "Поглинання", Material.GOLDEN_APPLE},
            {"health_boost", "Додаткове здоров'я", Material.APPLE},
            {"invisibility", "Невидимість", Material.FERMENTED_SPIDER_EYE},
            {"slow_falling", "Повільне падіння", Material.FEATHER},
            {"luck", "Удача", Material.EMERALD},
            {"saturation", "Насичення", Material.COOKED_BEEF},
            {"instant_health", "Миттєве лікування", Material.GLISTERING_MELON_SLICE},
            {"instant_damage", "Миттєва шкода", Material.SPIDER_EYE},
            {"poison", "Отрута", Material.POISONOUS_POTATO},
            {"wither", "Висушення", Material.WITHER_ROSE},
            {"slowness", "Уповільнення", Material.SOUL_SAND},
            {"weakness", "Слабкість", Material.BONE},
            {"blindness", "Сліпота", Material.INK_SAC},
            {"nausea", "Нудота", Material.ROTTEN_FLESH},
            {"hunger", "Голод", Material.BONE_MEAL},
            {"levitation", "Левітація", Material.SHULKER_SHELL},
            {"glowing", "Свічення", Material.GLOWSTONE_DUST},
            {"mining_fatigue", "Втома", Material.WOODEN_PICKAXE},
    };

    // палітра: hex, назва (45 кольорів)
    private static final String[][] PALETTE = {
            {"#000000", "Чорний"}, {"#0000AA", "Темно-синій"}, {"#00AA00", "Темно-зелений"}, {"#00AAAA", "Темно-бірюзовий"},
            {"#AA0000", "Темно-червоний"}, {"#AA00AA", "Фіолетовий"}, {"#FFAA00", "Золотий"}, {"#AAAAAA", "Сірий"},
            {"#555555", "Темно-сірий"}, {"#5555FF", "Синій"}, {"#55FF55", "Зелений"}, {"#55FFFF", "Бірюзовий"},
            {"#FF5555", "Червоний"}, {"#FF55FF", "Рожевий"}, {"#FFFF55", "Жовтий"}, {"#FFFFFF", "Білий"},
            {"#FF8800", "Помаранчевий"}, {"#FFB000", "Бурштин"}, {"#FFD700", "Золото"}, {"#F5DEB3", "Пшениця"},
            {"#C6FF00", "Лайм"}, {"#7CFC00", "Трава"}, {"#00FF7F", "Весна"}, {"#00FFAA", "М'ята"},
            {"#00CED1", "Бірюза"}, {"#008B8B", "Морська хвиля"}, {"#00BFFF", "Небо"}, {"#1E90FF", "Волошковий"},
            {"#4169E1", "Королівський синій"}, {"#4B0082", "Індиго"}, {"#8A2BE2", "Фіалка"}, {"#B388FF", "Лаванда"},
            {"#DA70D6", "Орхідея"}, {"#FF00FF", "Маджента"}, {"#FF69B4", "Яскравий рожевий"}, {"#FF1493", "Глибокий рожевий"},
            {"#FF007F", "Троянда"}, {"#DC143C", "Малиновий"}, {"#B22222", "Цегла"}, {"#800000", "Бордо"},
            {"#8B4513", "Коричневий"}, {"#D2691E", "Шоколад"}, {"#A0522D", "Сієна"}, {"#C0C0C0", "Срібло"},
            {"#2F2F2F", "Графіт"},
    };

    // змішані кольори (градієнти): назва, кольори...
    private static final String[][] GRADS = {
            {"Вогонь", "#ff0000", "#ffaa00", "#ffff00"}, {"Захід", "#ff512f", "#f09819"},
            {"Океан", "#00c6ff", "#0072ff"}, {"Лід", "#e0f7ff", "#4facfe"},
            {"Токсик", "#00ff87", "#f9ff00"}, {"Неон", "#ff00cc", "#3333ff"},
            {"Кров", "#4a0000", "#ff0000"}, {"Золото", "#f7971e", "#ffd200", "#fff6a0"},
            {"Лава", "#330000", "#ff2a00", "#ffae00"}, {"Райдуга", "#ff0000", "#ff9900", "#ffee00", "#33ff33", "#00ccff", "#7a3cff"},
            {"Космос", "#0f0c29", "#302b63", "#8e2de2", "#ff6ad5"}, {"М'ята", "#00ffa3", "#00d4ff"},
            {"Вишня", "#ff0844", "#ffb199"}, {"Ліс", "#0b3d0b", "#3ddc3d", "#b6ff7a"},
            {"Тьма", "#000000", "#434343", "#8a8a8a"}, {"Цукор", "#ff9a9e", "#fad0c4", "#fbc2eb"},
            {"Електрик", "#00f5ff", "#7c00ff", "#ff00e5"}, {"Ніч", "#141e30", "#243b55"},
            {"Пустеля", "#edde5d", "#ff8c42", "#a0522d"}, {"Кислота", "#ccff00", "#00ff66"},
            {"Фіолет", "#7f00ff", "#e100ff"}, {"Лід і вогонь", "#00e5ff", "#ffffff", "#ff3d00"},
            {"Ендер", "#1a0033", "#9d00ff", "#e0b3ff"}, {"Мідь", "#b87333", "#ffcba4"},
            {"Срібний", "#757f9a", "#d7dde8", "#ffffff"}, {"Смарагд", "#00b09b", "#96c93d"},
            {"Рубін", "#9d0208", "#ff4d6d"}, {"Аметист", "#5a189a", "#c77dff"},
            {"Сонце", "#ff7e00", "#ffcc00", "#fff200"}, {"Грім", "#ffee00", "#ffffff", "#00b4ff"},
            {"Персик", "#ff9966", "#ff5e62"}, {"Лагуна", "#43cea2", "#185a9d"},
            {"Полум'я", "#f12711", "#f5af19"}, {"Північ", "#232526", "#414345", "#ffffff"},
            {"Вампір", "#1a0000", "#8b0000", "#ff3b3b"}, {"Кристал", "#a8edea", "#fed6e3"},
    };

    private static final String[] FONT_KEYS = {null, "minecraft:uniform", "minecraft:alt", "minecraft:illageralt"};
    private static final String[] FONT_NAMES = {"Звичайний", "Uniform (юнікод)", "Мова зачарувань", "Рунічний (illager)"};
    private static final Material[] FONT_ICONS = {Material.BOOK, Material.WRITABLE_BOOK, Material.ENCHANTING_TABLE, Material.CROSSBOW};

    // стилі букв: назва. Режими 0-4 — BMP, 5+ — математичні алфавіти
    private static final String[] UNI_NAMES = {
            "Звичайний", "ꜱᴍᴀʟʟ ᴄᴀᴘꜱ", "ｆｕｌｌ ｗｉｄｔｈ", "Ⓒⓘⓡⓒⓛⓔⓓ", "ˢᵘᵖᵉʳˢᶜʳⁱᵖᵗ",
            "𝐁𝐨𝐥𝐝", "𝑩𝒐𝒍𝒅 𝑰𝒕𝒂𝒍𝒊𝒄", "𝓑𝓸𝓵𝓭 𝓢𝓬𝓻𝓲𝓹𝓽", "𝕭𝖔𝖑𝖉 𝕱𝖗𝖆𝖐𝖙𝖚𝖗", "𝔻𝕠𝕦𝕓𝕝𝕖-𝕤𝕥𝕣𝕦𝕔𝕜",
            "𝖲𝖺𝗇𝗌", "𝗦𝗮𝗻𝘀 𝗕𝗼𝗹𝗱", "𝘚𝘢𝘯𝘴 𝘐𝘵𝘢𝘭𝘪𝘤", "𝙎𝙖𝙣𝙨 𝘽𝙤𝙡𝙙 𝙄𝙩𝙖𝙡𝙞𝙘", "𝙼𝚘𝚗𝚘𝚜𝚙𝚊𝚌𝚎",
    };
    // для режимів 5..: {код 'A', код 'a', код '0' (або -1)}
    private static final int[][] MATH = {
            {0x1D400, 0x1D41A, 0x1D7CE}, {0x1D468, 0x1D482, -1}, {0x1D4D0, 0x1D4EA, -1}, {0x1D56C, 0x1D586, -1},
            {0x1D538, 0x1D552, 0x1D7D8}, {0x1D5A0, 0x1D5BA, 0x1D7E2}, {0x1D5D4, 0x1D5EE, 0x1D7EC},
            {0x1D608, 0x1D622, -1}, {0x1D63C, 0x1D656, -1}, {0x1D670, 0x1D68A, 0x1D7F6},
    };

    // Базові предмети для сфер і талісманів
    private static final Material[] SPHERE_BASES = {Material.PLAYER_HEAD};
    private static final Material[] TALISMAN_BASES = {Material.TOTEM_OF_UNDYING};

    private static final Set<Material> TOUGH = EnumSet.of(Material.OBSIDIAN, Material.CRYING_OBSIDIAN,
            Material.RESPAWN_ANCHOR, Material.ENDER_CHEST, Material.ENCHANTING_TABLE, Material.ANVIL,
            Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL, Material.ANCIENT_DEBRIS, Material.NETHERITE_BLOCK);

    // Вбудовані голови для сфер: {назва, Value}. Свої додавай у plugins/Forge/heads.yml (потім /forge reload)
    private static final String[][] BUILTIN_HEADS = {
            {"Spirit Dye", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWJhY2RiNmRkYTA0MzU4YWZmYzRjZDBlZDI3Yzc1NGY4ZmM0ZTBmNTUwMDc3MGNmNjQxODFlYzYxMzMyMmZjNCJ9fX0="},
            {"Зелений шар", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGM2YmFjZDM2ZWQ2MGY1MzMxMzhlNzU5YzQyNTk0NjIyMmI3OGVkYTZiNjE2MjE2ZjZkY2MwOGU5MGQzM2UifX19"},
            {"Кам'яний шар (жовтий)", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGNiZDA5NmY5MjQ4YjJhMjllZTk3NjFmZjA3MWE2NmU4YmU1ODFjMGZjYTE3NTI0ZDNlNDg1YzUwZjBkODVkMSJ9fX0="},
    };
    private final List<String[]> heads = new ArrayList<>();

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, String> tntBlocks = new HashMap<>();
    private boolean tntDirty = false;
    private final Random rnd = new Random();
    private NamespacedKey kType, kEff, kEffCol, kCE, kTnt, kBook, kBookLvl, kUid;
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Set<UUID> hammering = new HashSet<>();
    private boolean breaking = false;
    private Map<Material, Material> smeltMap;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        kType = new NamespacedKey(this, "forge_type");
        kEff = new NamespacedKey(this, "forge_effects");
        kEffCol = new NamespacedKey(this, "forge_effcolors");
        kCE = new NamespacedKey(this, "forge_ce");
        kTnt = new NamespacedKey(this, "forge_tnt");
        kBook = new NamespacedKey(this, "forge_book");
        kBookLvl = new NamespacedKey(this, "forge_booklvl");
        kUid = new NamespacedKey(this, "forge_uid");
        loadHeads();
        loadTnt();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::saveTnt, 100L, 100L);
    }

    @Override
    public void onDisable() {
        for (BukkitRunnable b : new ArrayList<>(blasts)) b.cancel();
        blasts.clear();
        tntDirty = true;
        saveTnt();
    }

    // ───────────────────────── голови з heads.yml ─────────────────────────

    /** Вбудовані голови + ті, що додані в plugins/Forge/heads.yml (Value, уся команда /give або посилання на текстуру). */
    private void loadHeads() {
        heads.clear();
        for (String[] h : BUILTIN_HEADS) heads.add(h);
        File f = new File(getDataFolder(), "heads.yml");
        if (!f.exists()) {
            try {
                getDataFolder().mkdirs();
                Files.write(f.toPath(), String.join("\n",
                        "# Додаткові голови для сфер (кнопка «Вигляд»).",
                        "# name  — назва в меню",
                        "# value — Value (рядок eyJ0ZXh0dXJlcyI...), або вся команда /give з mcheads.ru / minecraft-heads.com,",
                        "#         або посилання textures.minecraft.net/texture/...",
                        "# Після змін напиши в грі /forge reload",
                        "#",
                        "# Приклад:",
                        "# heads:",
                        "#   - name: \"Моя сфера\"",
                        "#     value: \"eyJ0ZXh0dXJlcyI6...\"",
                        "heads: []",
                        "").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                getLogger().warning("Не вдалось створити heads.yml: " + ex.getMessage());
            }
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        for (Map<?, ?> m : y.getMapList("heads")) {
            String name = String.valueOf(m.get("name"));
            String val = extractTexture(String.valueOf(m.get("value")));
            if (val == null) { getLogger().warning("heads.yml: не вдалось прочитати код для «" + name + "»"); continue; }
            heads.add(new String[]{name, val});
        }
    }

    private static String extractTexture(String raw) {
        Matcher m = Pattern.compile("eyJ[A-Za-z0-9+/=_-]{20,}").matcher(raw);
        if (m.find()) return m.group();
        Matcher u = Pattern.compile("textures\\.minecraft\\.net/texture/([0-9a-fA-F]{20,})").matcher(raw);
        if (u.find()) {
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + u.group(1) + "\"}}}";
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    // ───────────────────────── поставлені кастомні TNT ─────────────────────────

    private void loadTnt() {
        tntBlocks.clear();
        File f = new File(getDataFolder(), "tnt.yml");
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        for (String s : y.getStringList("tnt")) {
            int i = s.indexOf('|');
            if (i > 0) tntBlocks.put(s.substring(0, i), s.substring(i + 1));
        }
    }

    private void saveTnt() {
        if (!tntDirty) return;
        tntDirty = false;
        YamlConfiguration y = new YamlConfiguration();
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, String> e : tntBlocks.entrySet()) l.add(e.getKey() + "|" + e.getValue());
        y.set("tnt", l);
        try {
            getDataFolder().mkdirs();
            y.save(new File(getDataFolder(), "tnt.yml"));
        } catch (IOException ex) {
            getLogger().warning("Не вдалось зберегти tnt.yml: " + ex.getMessage());
        }
    }

    private String lkey(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    // ───────────────────────── команда ─────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Тільки для гравців."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("cancel")) {
            Session s = sessions.get(p.getUniqueId());
            if (s != null && s.pendingKind != null) handleInput(p, s, "-");
            return true;
        }
        if (!p.hasPermission("forge.admin")) { p.sendMessage(plain("&cНемає прав.")); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadHeads();
            p.sendMessage(plain("&aКонфіг і голови перезавантажено. Усього голів: &f" + heads.size()));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("stop")) {
            int n = blasts.size();
            for (BukkitRunnable b : new ArrayList<>(blasts)) b.cancel();
            blasts.clear();
            p.sendMessage(plain("&aЗупинено вибухів: &f" + n));
            return true;
        }
        ses(p);
        if (args.length > 0 && args[0].equalsIgnoreCase("books")) openBookCat(p); else openMain(p);
        return true;
    }

    // ───────────────────────── утиліти ─────────────────────────

    private Session ses(Player p) { return sessions.computeIfAbsent(p.getUniqueId(), k -> new Session()); }

    private Component plain(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(plain(name));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(this::plain).toList());
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack glow(ItemStack it, boolean on) {
        if (!on) return it;
        ItemMeta m = it.getItemMeta();
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    /** Іконка потрібного кольору (пофарбована шкіряна броня) з назвою цим кольором. */
    private ItemStack dye(Component name, String hex, String... lore) {
        ItemStack it = new ItemStack(Material.LEATHER_CHESTPLATE);
        LeatherArmorMeta lm = (LeatherArmorMeta) it.getItemMeta();
        TextColor tc = TextColor.fromHexString(hex);
        if (tc != null) lm.setColor(Color.fromRGB(tc.red(), tc.green(), tc.blue()));
        lm.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) lm.lore(Arrays.stream(lore).map(this::plain).toList());
        it.setItemMeta(lm);
        return it;
    }

    private Inventory create(Menu m, int page, int size, String title) {
        Holder h = new Holder(m, page);
        Inventory inv = Bukkit.createInventory(h, size, Component.text(title));
        h.inv = inv;
        return inv;
    }

    private void fill(Inventory inv) {
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, pane);
    }

    static String roman(int n) {
        if (n <= 0 || n > 3999) return String.valueOf(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { sb.append(s[i]); n -= v[i]; }
        return sb.toString();
    }

    static Integer fromRoman(String str) {
        String t = str.toUpperCase().trim();
        if (t.isEmpty()) return null;
        Map<Character, Integer> m = Map.of('I', 1, 'V', 5, 'X', 10, 'L', 50, 'C', 100, 'D', 500, 'M', 1000);
        int total = 0;
        for (int i = 0; i < t.length(); i++) {
            Integer cur = m.get(t.charAt(i));
            if (cur == null) return null;
            Integer next = i + 1 < t.length() ? m.get(t.charAt(i + 1)) : null;
            if (next != null && next > cur) total -= cur; else total += cur;
        }
        return total;
    }

    static Integer parseLevel(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fromRoman(s); }
    }

    static Double parseDouble(String s) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    static String fmt(double v) {
        return String.format(Locale.US, Math.abs(v - Math.rint(v)) < 0.0001 ? "%.0f" : "%.2f", v);
    }

    static String hex(String s) {
        String t = s.trim();
        if (!t.startsWith("#")) t = "#" + t;
        return TextColor.fromHexString(t) != null ? t.toLowerCase() : null;
    }

    private boolean gear(Cat c) {
        return c == Cat.WEAPON || c == Cat.ARMOR || c == Cat.TOOL;
    }

    private boolean direct(Cat c) {
        return c == Cat.SPHERE || c == Cat.TALISMAN || c == Cat.POTION || c == Cat.TNT;
    }

    private String type(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(kType, PersistentDataType.STRING);
    }

    private String ceLabel(CE ce, int lvl) {
        if (ce.id.equals("hammer")) {
            int[] d = hammerDims(lvl);
            return "Молот " + roman(lvl) + " (" + d[0] + "x" + d[1] + "x" + d[2] + ")";
        }
        return ce.max > 1 ? ce.name + " " + roman(lvl) : ce.name;
    }

    private ItemStack newItem(Cat cat, Material m, Session s) {
        ItemStack it = new ItemStack(m);
        switch (cat) {
            case SPHERE, TALISMAN -> {
                ItemMeta meta = it.getItemMeta();
                meta.getPersistentDataContainer().set(kType, PersistentDataType.STRING, cat == Cat.SPHERE ? "sphere" : "talisman");
                meta.displayName(plain(cat == Cat.SPHERE ? "&bСфера" : "&6Талісман"));
                it.setItemMeta(meta);
                refreshLore(it);
                if (cat == Cat.SPHERE && !heads.isEmpty()) it = applyHead(it, heads.get(0)[1]);
            }
            case POTION -> {
                PotionMeta pm = (PotionMeta) it.getItemMeta();
                pm.setColor(Color.fromRGB(0xB14CFF));
                pm.displayName(plain("&dЗілля"));
                it.setItemMeta(pm);
            }
            case TNT -> it = makeTnt(new double[]{10, 60});
            default -> { }
        }
        return it;
    }

    private List<Material> materials(Cat c) {
        List<Material> r = new ArrayList<>();
        switch (c) {
            case SPHERE -> r.addAll(Arrays.asList(SPHERE_BASES));
            case TALISMAN -> r.addAll(Arrays.asList(TALISMAN_BASES));
            case WEAPON -> {
                for (String t : new String[]{"WOODEN", "STONE", "COPPER", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"})
                    addRow(r, t + "_SWORD", t + "_AXE", t + "_SPEAR");
                addRow(r, "BOW", "CROSSBOW", "TRIDENT", "MACE");
            }
            case ARMOR -> {
                for (String t : new String[]{"LEATHER", "CHAINMAIL", "COPPER", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"})
                    addRow(r, t + "_HELMET", t + "_CHESTPLATE", t + "_LEGGINGS", t + "_BOOTS");
                addRow(r, "TURTLE_HELMET", "ELYTRA", "SHIELD");
            }
            case TOOL -> {
                for (String t : new String[]{"WOODEN", "STONE", "COPPER", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"})
                    addRow(r, t + "_PICKAXE", t + "_AXE", t + "_SHOVEL", t + "_HOE");
                addRow(r, "SHEARS", "FISHING_ROD", "FLINT_AND_STEEL", "BRUSH");
            }
            default -> { }
        }
        return r;
    }

    /** Додає рядок рівно з 9 слотів. Пропуски = null. */
    private void addRow(List<Material> out, String... names) {
        List<Material> row = new ArrayList<>();
        boolean any = false;
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            row.add(m);
            if (m != null) any = true;
        }
        if (!any) return;
        while (row.size() < 9) row.add(null);
        out.addAll(row);
    }

    // ───────────────────────── меню ─────────────────────────

    private void openMain(Player p) {
        Inventory inv = create(Menu.MAIN, 0, 27, "Forge — головне меню");
        fill(inv);
        inv.setItem(10, icon(Material.DIAMOND_SWORD, "&cЗброя"));
        inv.setItem(11, icon(Material.DIAMOND_CHESTPLATE, "&9Броня"));
        inv.setItem(12, icon(Material.DIAMOND_PICKAXE, "&aІнструменти", "&7Кірки, сокири, лопати, мотики"));
        inv.setItem(13, icon(Material.HEART_OF_THE_SEA, "&bСфери"));
        inv.setItem(14, icon(Material.TOTEM_OF_UNDYING, "&6Талісмани"));
        inv.setItem(15, icon(Material.POTION, "&dЗілля", "&7Свої ефекти, рівні, тривалість, колір"));
        inv.setItem(16, icon(Material.TNT, "&cКастомний TNT", "&7Сила вибуху без ліміту, запал"));
        inv.setItem(22, icon(Material.ENCHANTED_BOOK, "&5Книги зачарувань", "&7Окремо для кожного предмета:", "&7кірка, меч, броня, талісман...", "&7Накласти: книга на курсор → клік по предмету"));
        p.openInventory(inv);
    }

    private void openList(Player p, Cat cat, int page) {
        Session s = ses(p);
        s.cat = cat;
        List<Material> list = materials(cat);
        Inventory inv = create(Menu.LIST, page, 54, "Forge — оберіть предмет");
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            if (list.get(idx) == null) continue;
            ItemStack it = new ItemStack(list.get(idx));
            ItemMeta meta = it.getItemMeta();
            meta.lore(List.of(plain("&eНатисни, щоб редагувати")));
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "&7← Назад"));
        if ((page + 1) * 45 < list.size()) inv.setItem(53, icon(Material.ARROW, "&7Далі →"));
        inv.setItem(49, icon(Material.BARRIER, "&cДо головного меню"));
        fill(inv);
        p.openInventory(inv);
    }

    private void startDirect(Player p, Cat cat) {
        Session s = ses(p);
        s.cat = cat;
        s.base = switch (cat) {
            case SPHERE -> Material.PLAYER_HEAD;
            case POTION -> Material.POTION;
            case TNT -> Material.TNT;
            default -> Material.TOTEM_OF_UNDYING;
        };
        s.item = newItem(cat, s.base, s);
        s.ns = null;
        openEditor(p);
    }

    private void openEditor(Player p) {
        Session s = ses(p);
        Inventory inv = create(Menu.EDITOR, 0, 54, "Forge — редактор");
        inv.setItem(13, s.item.clone());
        switch (s.cat) {
            case WEAPON, ARMOR, TOOL -> {
                inv.setItem(29, icon(Material.ENCHANTING_TABLE, "&dЗачарування", "&7Будь-які рівні, навіть понад ванільні"));
                inv.setItem(30, icon(Material.ENCHANTED_BOOK, "&5Кастомні зачарування", "&7Молот, автоплавка, магніт, вампіризм..."));
                inv.setItem(31, icon(Material.IRON_SWORD, "&cАтрибути", "&7Сила (урон), броня, здоров'я, швидкість..."));
            }
            case SPHERE -> {
                inv.setItem(29, icon(Material.PLAYER_HEAD, "&6Вигляд", "&7Обрати голову-текстуру для предмета"));
                inv.setItem(31, icon(Material.IRON_SWORD, "&cАтрибути", "&7Сила (урон), броня, здоров'я, швидкість..."));
                inv.setItem(33, icon(Material.BREWING_STAND, "&bЕфекти", "&7Ефекти та колір їх рядків в описі"));
            }
            case TALISMAN -> {
                inv.setItem(29, icon(Material.ENCHANTED_BOOK, "&5Кастомні зачарування", "&7Захист, шипи, відновлення"));
                inv.setItem(31, icon(Material.IRON_SWORD, "&cАтрибути", "&7Сила (урон), броня, здоров'я, швидкість..."));
                inv.setItem(33, icon(Material.BREWING_STAND, "&bЕфекти", "&7Ефекти та колір їх рядків в описі"));
            }
            case POTION -> {
                inv.setItem(29, icon(s.item.getType(), "&eТип зілля", "&7Натисни, щоб змінити:", "&7звичайне → кидальне → затримуюче"));
                inv.setItem(30, icon(Material.PAPER, "&dКолір зілля", "&7Ввести в чаті (#rrggbb)"));
                inv.setItem(31, icon(Material.BREWING_STAND, "&bЕфекти зілля", "&7Рівень, тривалість, позитивні й негативні"));
            }
            case TNT -> inv.setItem(31, icon(Material.TNT, "&cПараметри TNT", "&7Сила вибуху без ліміту, запал", "&7Ламає все, навіть обсидіан"));
        }
        inv.setItem(38, icon(Material.NAME_TAG, "&eНазва", "&7Студія: кольори, градієнти, шрифти, стилі букв"));
        inv.setItem(40, icon(Material.EMERALD, "&aВидати собі"));
        inv.setItem(42, icon(Material.LAVA_BUCKET, "&cСкинути все"));
        inv.setItem(49, icon(Material.ARROW, "&7← Назад"));
        fill(inv);
        p.openInventory(inv);
    }

    // ── ванільні зачарування ──

    private List<Enchantment> enchs(ItemStack item) {
        List<Enchantment> r = new ArrayList<>();
        for (Enchantment e : Registry.ENCHANTMENT) if (e.canEnchantItem(item)) r.add(e);
        r.sort(Comparator.comparing(e -> e.getKey().toString()));
        return r;
    }

    private void openEnch(Player p, int page) {
        Session s = ses(p);
        List<Enchantment> list = enchs(s.item);
        Inventory inv = create(Menu.ENCH, page, 54, "Forge — зачарування");
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            Enchantment e = list.get(idx);
            int cur = s.item.getEnchantmentLevel(e);
            ItemStack it = new ItemStack(cur > 0 ? Material.ENCHANTED_BOOK : Material.BOOK);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(Component.translatable(e.translationKey()).color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    plain("&7Рівень: &f" + (cur > 0 ? roman(cur) + " (" + cur + ")" : "немає")),
                    plain("&8ЛКМ +1 · ПКМ −1"),
                    plain("&8Shift+ЛКМ +5 · Shift+ПКМ −5"),
                    plain("&8Колесо — ввести число або римську")));
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }
        if (list.isEmpty()) inv.setItem(22, icon(Material.BARRIER, "&cДля цього предмета немає ванільних зачарувань"));
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "&7← Назад"));
        if ((page + 1) * 45 < list.size()) inv.setItem(53, icon(Material.ARROW, "&7Далі →"));
        inv.setItem(49, icon(Material.BARRIER, "&cДо редактора"));
        fill(inv);
        p.openInventory(inv);
    }

    private void setEnch(ItemStack it, Enchantment en, int lvl) {
        if (lvl <= 0) it.removeEnchantment(en);
        else it.addUnsafeEnchantment(en, Math.min(lvl, 255));
    }

    // ── атрибути ──

    private void openAttr(Player p) {
        Session s = ses(p);
        Inventory inv = create(Menu.ATTR, 0, 27, "Forge — атрибути");
        for (int i = 0; i < ATTRS.length; i++) {
            String key = (String) ATTRS[i][0];
            double cur = getAttr(s.item, key);
            double step = (double) ATTRS[i][3];
            inv.setItem(i, icon((Material) ATTRS[i][2], "&e" + ATTRS[i][1],
                    "&7Додано: &f" + (cur == 0 ? "—" : (cur > 0 ? "+" : "") + cur),
                    "&8ЛКМ +" + step + " · ПКМ −" + step,
                    "&8Shift — половина кроку",
                    "&8Колесо — ввести своє (можна 0.5, 1.25 ...)"));
        }
        inv.setItem(22, icon(Material.BARRIER, "&cДо редактора"));
        fill(inv);
        p.openInventory(inv);
    }

    private ItemStack applyHead(ItemStack item, String value) {
        ItemStack it = item.withType(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) it.getItemMeta();
        PlayerProfile prof = Bukkit.createProfile(UUID.nameUUIDFromBytes(value.getBytes()), "forge");
        prof.setProperty(new ProfileProperty("textures", value));
        sm.setPlayerProfile(prof);
        it.setItemMeta(sm);
        return it;
    }

    private void openLook(Player p, int page) {
        Inventory inv = create(Menu.LOOK, page, 54, "Forge — вигляд");
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= heads.size()) break;
            ItemStack it = applyHead(new ItemStack(Material.PLAYER_HEAD), heads.get(idx)[1]);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(plain("&e" + heads.get(idx)[0]));
            meta.lore(List.of(plain("&7Натисни, щоб застосувати")));
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "&7← Назад"));
        if ((page + 1) * 45 < heads.size()) inv.setItem(53, icon(Material.ARROW, "&7Далі →"));
        inv.setItem(49, icon(Material.BARRIER, "&cДо редактора"));
        fill(inv);
        p.openInventory(inv);
    }

    // ── ефекти сфер/талісманів ──

    private void openEffect(Player p) {
        Session s = ses(p);
        Map<String, Integer> cur = getEffects(s.item);
        Inventory inv = create(Menu.EFFECT, 0, 36, "Forge — ефекти");
        for (int i = 0; i < EFFECTS.length; i++) {
            String key = (String) EFFECTS[i][0];
            int lvl = cur.getOrDefault(key, 0);
            String col = getEffColors(s.item).get(key);
            inv.setItem(i, icon((Material) EFFECTS[i][2], "&e" + EFFECTS[i][1],
                    "&7Рівень: &f" + (lvl > 0 ? roman(lvl) + " (" + lvl + ")" : "немає"),
                    "&7Колір рядка: &f" + (col == null ? "стандартний" : col),
                    s.colorMode ? "&aРежим кольору: клік — обрати колір" : "&8ЛКМ +1 · ПКМ −1 · Shift ±5",
                    "&8Колесо — ввести число або римську"));
        }
        inv.setItem(27, glow(icon(Material.PAINTING, "&dРежим кольору: " + (s.colorMode ? "&aУВІМКНЕНО" : "&cвимкнено"),
                "&7Коли увімкнено, клік по ефекту", "&7відкриває палітру для його рядка", "&7(Сила I, Швидкість II...)"), s.colorMode));
        inv.setItem(31, icon(Material.BARRIER, "&cДо редактора"));
        fill(inv);
        p.openInventory(inv);
    }

    // ── кастомні зачарування на предметі ──

    private List<CE> applicable(ItemStack it) {
        List<CE> r = new ArrayList<>();
        for (CE ce : CES.values()) if (ce.applies(it.getType())) r.add(ce);
        return r;
    }

    private void openCEnch(Player p) {
        Session s = ses(p);
        Map<String, Integer> have = getCE(s.item);
        List<CE> list = applicable(s.item);
        Inventory inv = create(Menu.CENCH, 0, 36, "Forge — кастомні зачарування");
        for (int i = 0; i < list.size() && i < 27; i++) {
            CE ce = list.get(i);
            int lvl = have.getOrDefault(ce.id, 0);
            inv.setItem(i, glow(icon(ce.icon, "&6" + ce.name,
                    "&7" + ce.desc,
                    "&7Зараз: &f" + (lvl > 0 ? ceLabel(ce, lvl) : "немає"),
                    "&7Макс. рівень: &f" + roman(ce.max) + " (" + ce.max + ")",
                    "&8ЛКМ +1 · ПКМ −1",
                    "&8Колесо — ввести число або римську"), lvl > 0));
        }
        if (list.isEmpty()) inv.setItem(13, icon(Material.BARRIER, "&cДля цього предмета немає кастомних зачарувань"));
        inv.setItem(31, icon(Material.BARRIER, "&cДо редактора"));
        fill(inv);
        p.openInventory(inv);
    }

    private Map<String, Integer> getCE(ItemStack it) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (it == null || !it.hasItemMeta()) return m;
        String raw = it.getItemMeta().getPersistentDataContainer().get(kCE, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return m;
        for (String part : raw.split(",")) {
            String[] a = part.split(":");
            if (a.length == 2) try { m.put(a[0], Integer.parseInt(a[1])); } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    private void setCE(ItemStack it, String id, int lvl) {
        Map<String, Integer> m = getCE(it);
        CE ce = CES.get(id);
        if (lvl <= 0) m.remove(id); else m.put(id, ce == null ? lvl : Math.min(lvl, ce.max));
        ItemMeta meta = it.getItemMeta();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        if (sb.length() == 0) meta.getPersistentDataContainer().remove(kCE);
        else meta.getPersistentDataContainer().set(kCE, PersistentDataType.STRING, sb.toString());
        it.setItemMeta(meta);
        refreshLore(it);
    }

    // ── безпека: унікальний ID, антиспам, кулдауни ──

    /** Прихований унікальний ID предмета (PDC). Видається при кожній видачі, щоб відрізняти оригінал від підробки/дюпа. */
    private ItemStack stamp(ItemStack it) {
        if (it == null || it.getType().isAir() || it.getType() == Material.TNT) return it;
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        m.getPersistentDataContainer().set(kUid, PersistentDataType.STRING, UUID.randomUUID().toString());
        it.setItemMeta(m);
        return it;
    }

    private String uidOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(kUid, PersistentDataType.STRING);
    }

    /** true, якщо гравець клікає швидше за security.click-delay-ms (захист від швидкого кліку / дюпів). */
    private boolean tooFast(Player p) {
        long gap = getConfig().getLong("security.click-delay-ms", 120L);
        if (gap <= 0) return false;
        long now = System.currentTimeMillis();
        Long last = lastClick.put(p.getUniqueId(), now);
        return last != null && now - last < gap;
    }

    /** Кулдаун здібності з відліком в ActionBar. true = можна використати (і кулдаун запущено). */
    private boolean useCooldown(Player p, String key, double sec) {
        if (sec <= 0) return true;
        Map<String, Long> m = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        long now = System.currentTimeMillis();
        Long until = m.get(key);
        if (until != null && until > now) {
            p.sendActionBar(plain("&c⏳ " + key + ": &f" + String.format(Locale.US, "%.1f", (until - now) / 1000.0) + " с"));
            return false;
        }
        m.put(key, now + (long) (sec * 1000));
        return true;
    }

    /** Предмети з пасивними ефектами: ліва рука + (за конфігом) весь хотбар. */
    private List<ItemStack> passiveItems(Player p) {
        List<ItemStack> out = new ArrayList<>();
        boolean hotbar = !"offhand".equalsIgnoreCase(getConfig().getString("passive.slots", "hotbar"));
        if (hotbar) for (int i = 0; i < 9; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (type(it) != null) out.add(it);
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (type(off) != null) out.add(off);
        return out;
    }

    /** Кастомні зачарування талісманів гравця; однакові НЕ складаються - береться найвищий рівень. */
    private Map<String, Integer> passiveCE(Player p) {
        Map<String, Integer> m = new HashMap<>();
        for (ItemStack it : passiveItems(p)) for (Map.Entry<String, Integer> e : getCE(it).entrySet()) m.merge(e.getKey(), e.getValue(), Math::max);
        return m;
    }

    /** Шукає клони: той самий forge_uid у двох слотах (чи у двох гравців) - зайві копії видаляє і пише в лог. */
    private void scanDupes() {
        Map<String, String> seen = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack[] c = p.getInventory().getContents();
            for (int i = 0; i < c.length; i++) {
                String uid = uidOf(c[i]);
                if (uid == null) continue;
                String where = p.getName() + "#" + i;
                if (seen.putIfAbsent(uid, where) != null) {
                    getLogger().warning("Дюп: предмет " + uid + " у " + where + " (перший: " + seen.get(uid) + ") - видалено");
                    p.getInventory().setItem(i, null);
                }
            }
        }
    }

    // ── книги ──

    private ItemStack makeBook(CE ce, int lvl) {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta m = it.getItemMeta();
        m.displayName(plain("&d&lКнига &8» &6" + ceLabel(ce, lvl)));
        m.lore(List.of(plain("&7" + ce.desc), plain(" "),
                plain("&aВізьми книгу на курсор і клікни"), plain("&aпо потрібному предмету")));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(kBook, PersistentDataType.STRING, ce.id);
        m.getPersistentDataContainer().set(kBookLvl, PersistentDataType.INTEGER, lvl);
        it.setItemMeta(m);
        return it;
    }

    private String bookId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(kBook, PersistentDataType.STRING);
    }

    private int bookLvl(ItemStack it) {
        Integer v = it.getItemMeta().getPersistentDataContainer().get(kBookLvl, PersistentDataType.INTEGER);
        return v == null ? 1 : v;
    }

    private void openBookCat(Player p) {
        Inventory inv = create(Menu.BOOKCAT, 0, 36, "Forge — книги: оберіть предмет");
        for (int i = 0; i < BCATS.length; i++) {
            Material m = Material.matchMaterial(BCATS[i][1]);
            inv.setItem(i, icon(m == null ? Material.BOOK : m, "&6" + BCATS[i][0], "&7Книги, що підходять саме до цього"));
        }
        inv.setItem(31, icon(Material.BARRIER, "&cДо головного меню"));
        fill(inv);
        p.openInventory(inv);
    }

    private List<Object> bookEntries(int cat) {
        List<Object> r = new ArrayList<>();
        Material mm = Material.matchMaterial(BCATS[cat][1]);
        if (mm == null) return r;
        ItemStack sample = new ItemStack(mm);
        List<Enchantment> ens = new ArrayList<>();
        for (Enchantment e : Registry.ENCHANTMENT) if (e.canEnchantItem(sample)) ens.add(e);
        ens.sort(Comparator.comparing(e -> e.getKey().toString()));
        r.addAll(ens);
        for (CE ce : CES.values()) if (ce.applies(mm)) r.add(ce);
        return r;
    }

    private void openBooks(Player p, int page) {
        Session s = ses(p);
        List<Object> list = bookEntries(s.bookCat);
        Inventory inv = create(Menu.BOOKS, page, 54, "Forge — книги: " + BCATS[s.bookCat][0]);
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            Object o = list.get(idx);
            ItemStack it;
            if (o instanceof Enchantment en) {
                it = new ItemStack(Material.ENCHANTED_BOOK);
                ItemMeta meta = it.getItemMeta();
                meta.displayName(Component.translatable(en.translationKey()).color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(plain("&7Ванільне зачарування"), plain("&8Макс. рівень: " + en.getMaxLevel()),
                        plain("&eЛКМ — книга I"), plain("&eПКМ — книга " + roman(en.getMaxLevel())),
                        plain("&8Колесо — свій рівень")));
                it.setItemMeta(meta);
            } else {
                CE ce = (CE) o;
                it = glow(icon(ce.icon, "&6" + ce.name, "&7" + ce.desc, "&8Макс. рівень: " + ce.max,
                        "&eЛКМ — книга I", "&eПКМ — книга " + roman(ce.max)), true);
            }
            inv.setItem(i, it);
        }
        if (list.isEmpty()) inv.setItem(22, icon(Material.BARRIER, "&cДля цього предмета немає книг"));
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "&7← Назад"));
        if ((page + 1) * 45 < list.size()) inv.setItem(53, icon(Material.ARROW, "&7Далі →"));
        inv.setItem(49, icon(Material.BARRIER, "&cДо списку предметів"));
        fill(inv);
        p.openInventory(inv);
    }

    private void giveBook(Player p, Object o, int lvl) {
        if (o instanceof Enchantment en) {
            ItemStack b = new ItemStack(Material.ENCHANTED_BOOK);
            EnchantmentStorageMeta m = (EnchantmentStorageMeta) b.getItemMeta();
            m.addStoredEnchant(en, Math.max(1, Math.min(lvl, 255)), true);
            b.setItemMeta(m);
            p.getInventory().addItem(stamp(b));
            p.sendMessage(plain("&aКнигу видано: &e" + en.getKey().getKey() + " " + roman(lvl)));
        } else {
            CE ce = (CE) o;
            int l = Math.max(1, Math.min(lvl, ce.max));
            p.getInventory().addItem(stamp(makeBook(ce, l)));
            p.sendMessage(plain("&aКнигу видано: &6" + ceLabel(ce, l)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBookApply(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack cur = e.getCursor(), tgt = e.getCurrentItem();
        if (cur == null || cur.getType() != Material.ENCHANTED_BOOK || tgt == null || tgt.getType().isAir()) return;
        String id = bookId(cur);
        if (id == null) return;
        if (e.getClick() != ClickType.LEFT) return;
        e.setCancelled(true);
        if (tooFast(p)) return;
        CE ce = CES.get(id);
        int lvl = bookLvl(cur);
        if (ce == null || !ce.applies(tgt.getType())) { p.sendMessage(plain("&cЦя книга не підходить до цього предмета.")); return; }
        int have = getCE(tgt).getOrDefault(id, 0);
        if (have >= lvl) { p.sendMessage(plain("&eНа предметі вже є це зачарування такого або вищого рівня.")); return; }
        int cap = getConfig().getInt("books.max-level", 0);
        if (cap > 0 && lvl > cap) { p.sendMessage(plain("&cЛіміт рівня книг на сервері: &f" + cap)); return; }
        double chance = getConfig().getDouble("books.success-chance", 100.0) - getConfig().getDouble("books.level-penalty", 0.0) * (lvl - 1);
        chance = Math.max(0, Math.min(100, chance));
        boolean ok = rnd.nextDouble() * 100.0 < chance;
        if (!ok) {
            if (getConfig().getBoolean("books.destroy-on-fail", true)) {
                if (cur.getAmount() <= 1) e.setCursor(null);
                else { cur.setAmount(cur.getAmount() - 1); e.setCursor(cur); }
            }
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_BREAK, 1f, 1f);
            p.sendMessage(plain("&cНевдача! Шанс був &f" + fmt(chance) + "%"));
            Bukkit.getScheduler().runTask(this, p::updateInventory);
            return;
        }
        setCE(tgt, id, lvl);
        if (cur.getAmount() <= 1) e.setCursor(null);
        else { cur.setAmount(cur.getAmount() - 1); e.setCursor(cur); }
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
        p.sendMessage(plain("&aНакладено: &6" + ceLabel(ce, Math.min(lvl, ce.max))));
        Bukkit.getScheduler().runTask(this, p::updateInventory);
    }

    // ── зілля ──

    private void cyclePotion(Session s) {
        Material m = s.item.getType();
        Material n = m == Material.POTION ? Material.SPLASH_POTION : m == Material.SPLASH_POTION ? Material.LINGERING_POTION : Material.POTION;
        s.item = s.item.withType(n);
    }

    /** {рівень, тривалість у секундах} */
    private double[] potionEff(ItemStack it, String key) {
        if (!(it.getItemMeta() instanceof PotionMeta pm)) return new double[]{0, 0};
        for (PotionEffect pe : pm.getCustomEffects())
            if (pe.getType().getKey().getKey().equals(key)) return new double[]{pe.getAmplifier() + 1, pe.getDuration() / 20.0};
        return new double[]{0, 0};
    }

    private void setPotionEff(ItemStack it, String key, int lvl, double sec) {
        PotionEffectType t = Registry.MOB_EFFECT.get(NamespacedKey.minecraft(key));
        if (t == null || !(it.getItemMeta() instanceof PotionMeta pm)) return;
        pm.removeCustomEffect(t);
        if (lvl > 0) pm.addCustomEffect(new PotionEffect(t, Math.max(1, (int) Math.round(sec * 20)), Math.min(lvl, 255) - 1, false, true, true), true);
        it.setItemMeta(pm);
    }

    private void openPEffect(Player p) {
        Session s = ses(p);
        Inventory inv = create(Menu.PEFFECT, 0, 36, "Forge — ефекти зілля");
        for (int i = 0; i < PEFFECTS.length; i++) {
            String key = (String) PEFFECTS[i][0];
            double[] cur = potionEff(s.item, key);
            inv.setItem(i, glow(icon((Material) PEFFECTS[i][2], "&e" + PEFFECTS[i][1],
                    "&7Рівень: &f" + (cur[0] > 0 ? roman((int) cur[0]) : "немає"),
                    "&7Тривалість: &f" + (cur[0] > 0 ? fmt(cur[1]) + " с" : "—"),
                    "&8ЛКМ рівень +1 · ПКМ −1",
                    "&8Shift+ЛКМ тривалість +30с · Shift+ПКМ −30с",
                    "&8Колесо — ввести: рівень тривалість_с (можна 0.5)"), cur[0] > 0));
        }
        inv.setItem(31, icon(Material.BARRIER, "&cДо редактора"));
        fill(inv);
        p.openInventory(inv);
    }

    // ── TNT ──

    private static final double[] TNT_PRESETS = {10, 50, 100, 500, 1000};
    private final List<BukkitRunnable> blasts = new ArrayList<>();

    /** {сила вибуху (без ліміту), запал у тіках}. Старі записи з 6 полями теж читаються. */
    private double[] parseTnt(String raw) {
        double[] d = {10, 60};
        String[] a = raw.split("[;,]");
        for (int i = 0; i < a.length && i < 2; i++) try { d[i] = Double.parseDouble(a[i].trim()); } catch (NumberFormatException ignored) { }
        return d;
    }

    private String tntStr(double[] d) {
        return String.format(Locale.US, "%.2f;%d", d[0], (int) d[1]);
    }

    /** Ліміту сили немає (tnt.max-power: 0 у config.yml). Якщо задати число більше 0 - це стеля. */
    private double clampPower(double v) {
        double max = getConfig().getDouble("tnt.max-power", 0);
        v = Math.max(0.5, v);
        return max > 0 ? Math.min(max, v) : v;
    }

    private double[] getTnt(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return new double[]{10, 60};
        String raw = it.getItemMeta().getPersistentDataContainer().get(kTnt, PersistentDataType.STRING);
        return raw == null ? new double[]{10, 60} : parseTnt(raw);
    }

    private Component tntName(double[] d) { return plain("&cTNT &8[&f" + fmt(d[0]) + "&8]"); }

    private ItemStack makeTnt(double[] d) {
        ItemStack it = new ItemStack(Material.TNT);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(tntName(d));
        meta.getPersistentDataContainer().set(kTnt, PersistentDataType.STRING, tntStr(d));
        it.setItemMeta(meta);
        return it;
    }

    private void setTnt(ItemStack it, double[] d) {
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(kTnt, PersistentDataType.STRING, tntStr(d));
        // назву із силою оновлюємо, лише якщо її не міняли в студії назв
        Component cur = meta.displayName();
        if (cur == null || PlainTextComponentSerializer.plainText().serialize(cur).startsWith("TNT [")) meta.displayName(tntName(d));
        it.setItemMeta(meta);
    }

    private void openTnt(Player p) {
        Session s = ses(p);
        double[] d = getTnt(s.item);
        Inventory inv = create(Menu.TNT, 0, 27, "Forge — параметри TNT");
        inv.setItem(10, icon(Material.TNT, "&cСила вибуху: &f" + fmt(d[0]), "&7Ліміту немає. Ламає все, навіть обсидіан",
                "&8ЛКМ +1 · ПКМ −1 · Shift ±10", "&8Колесо — ввести будь-яке число"));
        inv.setItem(11, icon(Material.CLOCK, "&eЗапал: &f" + fmt(d[1] / 20.0) + " с",
                "&8ЛКМ +0.5с · ПКМ −0.5с · Shift ±2с", "&8Колесо — ввести секунди (можна 1.5)"));
        for (int i = 0; i < TNT_PRESETS.length; i++)
            inv.setItem(12 + i, glow(icon(Material.GUNPOWDER, "&6Сила " + fmt(TNT_PRESETS[i]), "&eНатисни, щоб поставити"), d[0] == TNT_PRESETS[i]));
        inv.setItem(22, icon(Material.BARRIER, "&cДо редактора"));
        fill(inv);
        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack hand = e.getItemInHand();
        if (type(hand) != null) { e.setCancelled(true); return; }
        if (hand.getType() != Material.TNT) return;
        String key = lkey(e.getBlockPlaced().getLocation());
        boolean custom = hand.hasItemMeta() && hand.getItemMeta().getPersistentDataContainer().has(kTnt, PersistentDataType.STRING);
        if (!custom) {
            if (tntBlocks.remove(key) != null) tntDirty = true;
            return;
        }
        tntBlocks.put(key, tntStr(getTnt(hand)));
        tntDirty = true;
        e.getPlayer().sendActionBar(plain("&cКастомний TNT поставлено. Підпали його: кремінь і сталь, вогонь або редстоун."));
    }

    /** Коли блок TNT підпалюється, на його місці з'являється TNTPrimed - тоді підставляємо параметри. */
    @EventHandler
    public void onTntSpawn(EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof TNTPrimed t)) return;
        String raw = tntBlocks.remove(lkey(t.getLocation()));
        if (raw == null) return;
        tntDirty = true;
        double[] d = parseTnt(raw);
        if (t.getFuseTicks() >= 80) t.setFuseTicks(Math.max(1, (int) d[1]));
        t.setYield(1f);              // блоки рве власний вибух плагіна, ваніль - лише ефект
        t.setIsIncendiary(false);
        t.getPersistentDataContainer().set(kTnt, PersistentDataType.STRING, raw);
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        if (!(e.getEntity() instanceof TNTPrimed t)) return;
        String raw = t.getPersistentDataContainer().get(kTnt, PersistentDataType.STRING);
        if (raw == null) return;
        e.blockList().clear();
        blast(e.getLocation(), clampPower(parseTnt(raw)[0]));
    }

    /**
     * Сферичний вибух будь-якої сили: зносить усі блоки (обсидіан теж, крім бедроку і рідин)
     * в радіусі = сила. Працює порціями по кілька мс за тік, тому великий вибух не вішає сервер.
     * Незавантажені чанки не чіпає і не генерує.
     */
    private void blast(Location c, double power) {
        World w = c.getWorld();
        int bx = c.getBlockX(), by = c.getBlockY(), bz = c.getBlockZ();
        int r = (int) Math.ceil(power);
        double r2 = power * power;
        int minY = Math.max(w.getMinHeight(), by - r), maxY = Math.min(w.getMaxHeight() - 1, by + r);
        long budget = Math.max(5L, getConfig().getLong("tnt.blast-ms-per-tick", 25L)) * 1_000_000L;
        final boolean cube = "cube".equalsIgnoreCase(getConfig().getString("tnt.shape", "sphere"));
        final boolean pierceWater = getConfig().getBoolean("tnt.pierce-water", true);
        final boolean breakTough = getConfig().getBoolean("tnt.break-obsidian", true);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1, 0, 0, 0, 0);
        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.7f);
        double reach = Math.min(power, 160);
        for (Entity en : w.getNearbyEntities(c, reach, reach, reach)) {
            if (!(en instanceof LivingEntity le)) continue;
            double dist = le.getLocation().distance(c);
            if (dist > reach) continue;
            le.damage(Math.max(4, 60 * (1 - dist / reach)));
        }
        BukkitRunnable run = new BukkitRunnable() {
            int x = bx - r, z = bz - r;
            @Override public void run() {
                long end = System.nanoTime() + budget;
                while (x <= bx + r) {
                    int dx = x - bx, dz = z - bz;
                    double rem = cube ? 0 : r2 - dx * dx - dz * dz;
                    if (rem >= 0 && w.isChunkLoaded(x >> 4, z >> 4)) {
                        int h = cube ? r : (int) Math.floor(Math.sqrt(rem));
                        for (int y = Math.max(minY, by - h); y <= Math.min(maxY, by + h); y++) {
                            Block b = w.getBlockAt(x, y, z);
                            Material m = b.getType();
                            if (m.isAir() || m.getHardness() < 0) continue;
                            if (b.isLiquid() && !pierceWater) continue;
                            if (!breakTough && TOUGH.contains(m)) continue;
                            b.setType(Material.AIR, false);
                        }
                    }
                    if (++z > bz + r) { z = bz - r; x++; }
                    if (System.nanoTime() > end) return;
                }
                cancel();
                blasts.remove(this);
            }
        };
        blasts.add(run);
        run.runTaskTimer(this, 1L, 1L);
    }

    // ── студія назв ──

    private NameStyle nameStyle(Session s) {
        if (s.ns == null) {
            s.ns = new NameStyle();
            ItemMeta m = s.item.getItemMeta();
            if (m != null && m.hasDisplayName()) s.ns.text = PlainTextComponentSerializer.plainText().serialize(m.displayName());
            else s.ns.text = "Предмет";
        }
        return s.ns;
    }

    static String styleText(String s, int mode) {
        if (mode == 0) return s;
        StringBuilder sb = new StringBuilder();
        if (mode <= 4) {
            String small = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
            String sup = "ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖqʳˢᵗᵘᵛʷˣʸᶻ";
            for (char c : s.toCharArray()) {
                char lo = Character.toLowerCase(c);
                boolean up = c >= 'A' && c <= 'Z', low = c >= 'a' && c <= 'z', dig = c >= '0' && c <= '9';
                switch (mode) {
                    case 1 -> sb.append(up || low ? small.charAt(lo - 'a') : c);
                    case 2 -> {
                        if (up) sb.append((char) (0xFF21 + (c - 'A')));
                        else if (low) sb.append((char) (0xFF41 + (c - 'a')));
                        else if (dig) sb.append((char) (0xFF10 + (c - '0')));
                        else sb.append(c);
                    }
                    case 3 -> {
                        if (up) sb.append((char) (0x24B6 + (c - 'A')));
                        else if (low) sb.append((char) (0x24D0 + (c - 'a')));
                        else if (c >= '1' && c <= '9') sb.append((char) (0x2460 + (c - '1')));
                        else if (c == '0') sb.append('\u24EA');
                        else sb.append(c);
                    }
                    default -> sb.append(up || low ? sup.charAt(lo - 'a') : c);
                }
            }
            return sb.toString();
        }
        int[] m = MATH[Math.min(MATH.length - 1, mode - 5)];
        boolean dbl = mode - 5 == 4;
        for (char c : s.toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                if (dbl && "CHNPQRZ".indexOf(c) >= 0) {
                    int[] ex = {0x2102, 0x210D, 0x2115, 0x2119, 0x211A, 0x211D, 0x2124};
                    sb.appendCodePoint(ex["CHNPQRZ".indexOf(c)]);
                } else sb.appendCodePoint(m[0] + (c - 'A'));
            } else if (c >= 'a' && c <= 'z') sb.appendCodePoint(m[1] + (c - 'a'));
            else if (c >= '0' && c <= '9' && m[2] >= 0) sb.appendCodePoint(m[2] + (c - '0'));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Колір по градієнту з кількох стопів. */
    private TextColor lerpColor(List<TextColor> cs, double t) {
        if (cs.size() == 1) return cs.get(0);
        double pos = Math.max(0, Math.min(1, t)) * (cs.size() - 1);
        int i0 = (int) Math.floor(pos);
        if (i0 >= cs.size() - 1) i0 = cs.size() - 2;
        double f = pos - i0;
        TextColor a = cs.get(i0), b = cs.get(i0 + 1);
        return TextColor.color((int) Math.round(a.red() + (b.red() - a.red()) * f),
                (int) Math.round(a.green() + (b.green() - a.green()) * f),
                (int) Math.round(a.blue() + (b.blue() - a.blue()) * f));
    }

    private Component colored(String txt, List<String> hexes) {
        List<TextColor> cs = new ArrayList<>();
        for (String h : hexes) { TextColor c = TextColor.fromHexString(h); if (c != null) cs.add(c); }
        if (cs.isEmpty()) return Component.text(txt, NamedTextColor.WHITE);
        if (cs.size() == 1) return Component.text(txt, cs.get(0));
        Component root = Component.empty();
        int[] cps = txt.codePoints().toArray();
        int n = cps.length;
        for (int i = 0; i < n; i++)
            root = root.append(Component.text(new String(Character.toChars(cps[i])), lerpColor(cs, n <= 1 ? 0 : (double) i / (n - 1))));
        return root;
    }

    private Component buildName(NameStyle ns) {
        Component root = Component.empty()
                .decoration(TextDecoration.ITALIC, ns.i)
                .decoration(TextDecoration.BOLD, ns.b)
                .decoration(TextDecoration.UNDERLINED, ns.u)
                .decoration(TextDecoration.STRIKETHROUGH, ns.st)
                .decoration(TextDecoration.OBFUSCATED, ns.ob);
        if (ns.font > 0) root = root.font(Key.key(FONT_KEYS[ns.font]));
        return root.append(colored(styleText(ns.text, ns.uni), ns.cols));
    }

    private void applyName(Session s) {
        ItemMeta meta = s.item.getItemMeta();
        meta.displayName(buildName(nameStyle(s)));
        s.item.setItemMeta(meta);
    }

    private void openName(Player p) {
        Session s = ses(p);
        NameStyle ns = nameStyle(s);
        Inventory inv = create(Menu.NAME, 0, 54, "Forge — студія назв");
        inv.setItem(4, s.item.clone());
        inv.setItem(10, icon(Material.PAINTING, "&aКольори", "&7Палітра: 45 кольорів"));
        inv.setItem(11, icon(Material.AMETHYST_SHARD, "&5Змішані кольори (градієнти)", "&7Готові: вогонь, океан, райдуга...", "&7" + GRADS.length + " варіантів"));
        inv.setItem(12, icon(Material.PAPER, "&dСвій колір", "&7Ввести hex у чаті: #rrggbb"));
        inv.setItem(13, icon(Material.NAUTILUS_SHELL, "&dСвій градієнт", "&7Від 2 до 6 кольорів через пробіл:", "&7#ff0000 #ffff00 #00ff00"));
        inv.setItem(14, icon(Material.BARRIER, "&cПрибрати колір", ns.cols.isEmpty() ? "&8Зараз: білий" : "&7Зараз: " + String.join(" ", ns.cols)));
        inv.setItem(19, glow(icon(Material.NETHERITE_INGOT, "&lЖирний", ns.b ? "&aувімкнено" : "&8вимкнено"), ns.b));
        inv.setItem(20, glow(icon(Material.FEATHER, "&oКурсив", ns.i ? "&aувімкнено" : "&8вимкнено"), ns.i));
        inv.setItem(21, glow(icon(Material.STRING, "&nПідкреслений", ns.u ? "&aувімкнено" : "&8вимкнено"), ns.u));
        inv.setItem(22, glow(icon(Material.SHEARS, "&mЗакреслений", ns.st ? "&aувімкнено" : "&8вимкнено"), ns.st));
        inv.setItem(23, glow(icon(Material.ENDER_EYE, "&kXX&r &fМерехтливий", ns.ob ? "&aувімкнено" : "&8вимкнено"), ns.ob));
        for (int i = 0; i < 4; i++)
            inv.setItem(28 + i, glow(icon(FONT_ICONS[i], "&bШрифт: &f" + FONT_NAMES[i], "&7Системний шрифт Minecraft", "&eНатисни, щоб обрати"), ns.font == i));
        inv.setItem(33, icon(Material.COMPARATOR, "&eСтиль букв: &f" + UNI_NAMES[ns.uni],
                "&7" + UNI_NAMES.length + " стилів (латиниця)", "&eНатисни, щоб обрати"));
        inv.setItem(35, icon(Material.NAME_TAG, "&aВвести текст", "&7Поточний: &f" + ns.text));
        inv.setItem(49, icon(Material.ARROW, "&7← До редактора"));
        fill(inv);
        p.openInventory(inv);
    }

    private void openColor(Player p, String target) {
        Session s = ses(p);
        s.colorTarget = target;
        Inventory inv = create(Menu.NCOLOR, 0, 54, "Forge — палітра кольорів");
        for (int i = 0; i < PALETTE.length && i < 45; i++)
            inv.setItem(i, dye(Component.text(PALETTE[i][1], TextColor.fromHexString(PALETTE[i][0])), PALETTE[i][0], "&7" + PALETTE[i][0], "&eНатисни, щоб обрати"));
        inv.setItem(48, icon(Material.PAPER, "&dСвій колір", "&7Ввести hex: #rrggbb"));
        inv.setItem(49, icon(Material.ARROW, "&7← Назад"));
        fill(inv);
        p.openInventory(inv);
    }

    private void openGrad(Player p) {
        Inventory inv = create(Menu.NGRAD, 0, 54, "Forge — змішані кольори");
        for (int i = 0; i < GRADS.length && i < 45; i++) {
            List<String> cols = new ArrayList<>(Arrays.asList(GRADS[i]).subList(1, GRADS[i].length));
            inv.setItem(i, dye(colored(GRADS[i][0], cols).decoration(TextDecoration.BOLD, true), cols.get(0),
                    "&7" + String.join(" → ", cols), "&eНатисни, щоб обрати"));
        }
        inv.setItem(48, icon(Material.NAUTILUS_SHELL, "&dСвій градієнт", "&7Від 2 до 6 hex-кольорів через пробіл"));
        inv.setItem(49, icon(Material.ARROW, "&7← Назад"));
        fill(inv);
        p.openInventory(inv);
    }

    private void openStyle(Player p) {
        Session s = ses(p);
        NameStyle ns = nameStyle(s);
        Inventory inv = create(Menu.NSTYLE, 0, 36, "Forge — стиль букв");
        for (int i = 0; i < UNI_NAMES.length && i < 27; i++) {
            ItemStack it = icon(Material.BOOK, "&f" + UNI_NAMES[i], "&7Приклад:", "&f" + styleText("Intertime Sword 123", i), "&eНатисни, щоб обрати");
            inv.setItem(i, glow(it, ns.uni == i));
        }
        inv.setItem(31, icon(Material.ARROW, "&7← Назад"));
        fill(inv);
        p.openInventory(inv);
    }

    private void applyColor(Player p, Session s, String hex) {
        String t = s.colorTarget == null ? "name" : s.colorTarget;
        if (t.startsWith("eff:")) {
            setEffColor(s.item, t.substring(4), hex);
            openEffect(p);
        } else {
            NameStyle ns = nameStyle(s);
            ns.cols = new ArrayList<>(List.of(hex));
            applyName(s);
            openName(p);
        }
    }

    // ───────────────────────── атрибути ─────────────────────────

    private Attribute attr(String key) { return Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key)); }

    private EquipmentSlot primarySlot(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET")) return EquipmentSlot.HEAD;
        if (n.endsWith("_CHESTPLATE")) return EquipmentSlot.CHEST;
        if (n.endsWith("_LEGGINGS")) return EquipmentSlot.LEGS;
        if (n.endsWith("_BOOTS")) return EquipmentSlot.FEET;
        return EquipmentSlot.HAND;
    }

    private EquipmentSlotGroup slotGroup(ItemStack it) {
        if (type(it) != null) return EquipmentSlotGroup.OFFHAND;
        return switch (primarySlot(it.getType())) {
            case HEAD -> EquipmentSlotGroup.HEAD;
            case CHEST -> EquipmentSlotGroup.CHEST;
            case LEGS -> EquipmentSlotGroup.LEGS;
            case FEET -> EquipmentSlotGroup.FEET;
            default -> EquipmentSlotGroup.MAINHAND;
        };
    }

    /** Щоб меч не втратив свій базовий урон після додавання свого модифікатора. */
    private void ensureDefaults(ItemStack it, ItemMeta meta) {
        if (meta.hasAttributeModifiers()) return;
        Multimap<Attribute, AttributeModifier> d = it.getType().getDefaultAttributeModifiers(primarySlot(it.getType()));
        for (Map.Entry<Attribute, AttributeModifier> e : d.entries()) meta.addAttributeModifier(e.getKey(), e.getValue());
    }

    private double getAttr(ItemStack it, String key) {
        ItemMeta meta = it.getItemMeta();
        Attribute a = attr(key);
        if (meta == null || a == null) return 0;
        Collection<AttributeModifier> col = meta.getAttributeModifiers(a);
        NamespacedKey nk = new NamespacedKey(this, "forge_" + key);
        if (col != null) for (AttributeModifier m : col) if (m.getKey().equals(nk)) return m.getAmount();
        return 0;
    }

    private void setAttr(ItemStack it, String key, double v) {
        Attribute a = attr(key);
        if (a == null) return;
        ItemMeta meta = it.getItemMeta();
        ensureDefaults(it, meta);
        NamespacedKey nk = new NamespacedKey(this, "forge_" + key);
        Collection<AttributeModifier> col = meta.getAttributeModifiers(a);
        if (col != null) for (AttributeModifier m : new ArrayList<>(col)) if (m.getKey().equals(nk)) meta.removeAttributeModifier(a, m);
        v = Math.round(v * 1000.0) / 1000.0;
        if (v != 0) meta.addAttributeModifier(a, new AttributeModifier(nk, v, AttributeModifier.Operation.ADD_NUMBER, slotGroup(it)));
        it.setItemMeta(meta);
    }

    // ───────────────────────── ефекти сфер/талісманів ─────────────────────────

    private Map<String, Integer> getEffects(ItemStack it) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (it == null || !it.hasItemMeta()) return m;
        String raw = it.getItemMeta().getPersistentDataContainer().get(kEff, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return m;
        for (String part : raw.split(",")) {
            String[] a = part.split(":");
            if (a.length == 2) try { m.put(a[0], Integer.parseInt(a[1])); } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    private void setEffect(ItemStack it, String key, int lvl) {
        Map<String, Integer> m = getEffects(it);
        if (lvl <= 0) m.remove(key); else m.put(key, Math.min(lvl, 255));
        ItemMeta meta = it.getItemMeta();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        meta.getPersistentDataContainer().set(kEff, PersistentDataType.STRING, sb.toString());
        it.setItemMeta(meta);
        refreshLore(it);
    }

    private Map<String, String> getEffColors(ItemStack it) {
        Map<String, String> m = new HashMap<>();
        if (it == null || !it.hasItemMeta()) return m;
        String raw = it.getItemMeta().getPersistentDataContainer().get(kEffCol, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return m;
        for (String part : raw.split(",")) {
            String[] a = part.split("=");
            if (a.length == 2) m.put(a[0], a[1]);
        }
        return m;
    }

    private void setEffColor(ItemStack it, String key, String hex) {
        Map<String, String> m = getEffColors(it);
        m.put(key, hex);
        ItemMeta meta = it.getItemMeta();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        meta.getPersistentDataContainer().set(kEffCol, PersistentDataType.STRING, sb.toString());
        it.setItemMeta(meta);
        refreshLore(it);
    }

    private String effName(String key) {
        for (Object[] e : EFFECTS) if (e[0].equals(key)) return (String) e[1];
        return key;
    }

    /** Збирає опис предмета: ефекти сфери/талісмана, кастомні зачарування. Предмет із кастомним зачаруванням переливається. */
    private void refreshLore(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        String t = type(it);
        if (t != null) {
            Map<String, String> cols = getEffColors(it);
            for (Map.Entry<String, Integer> e : getEffects(it).entrySet()) {
                TextColor tc = TextColor.fromHexString(cols.getOrDefault(e.getKey(), "#55ffff"));
                lore.add(Component.text(effName(e.getKey()) + " " + roman(e.getValue()), tc == null ? NamedTextColor.AQUA : tc)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        Map<String, Integer> ces = getCE(it);
        for (Map.Entry<String, Integer> e : ces.entrySet()) {
            CE ce = CES.get(e.getKey());
            if (ce != null) lore.add(plain("&7" + ceLabel(ce, e.getValue())));
        }
        if (t != null) lore.add(plain("offhand".equalsIgnoreCase(getConfig().getString("passive.slots", "hotbar")) ? "&8Діє тільки в лівій руці" : "&8Діє в хотбарі або в лівій руці"));
        meta.lore(lore.isEmpty() ? null : lore);
        meta.setEnchantmentGlintOverride(ces.isEmpty() ? null : Boolean.TRUE);
        it.setItemMeta(meta);
    }

    private void applyEffects(Player p, ItemStack it) {
        for (Map.Entry<String, Integer> e : getEffects(it).entrySet()) {
            PotionEffectType t = Registry.MOB_EFFECT.get(NamespacedKey.minecraft(e.getKey()));
            if (t != null) p.addPotionEffect(new PotionEffect(t, 100, e.getValue() - 1, true, false, true));
        }
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            List<ItemStack> items = passiveItems(p);
            if (items.isEmpty()) continue;
            // однакові ефекти не стакаються: з кількох предметів беремо найвищий рівень
            Map<String, Integer> best = new HashMap<>();
            for (ItemStack it : items) for (Map.Entry<String, Integer> e : getEffects(it).entrySet()) best.merge(e.getKey(), e.getValue(), Math::max);
            for (Map.Entry<String, Integer> e : best.entrySet()) {
                PotionEffectType t = Registry.MOB_EFFECT.get(NamespacedKey.minecraft(e.getKey()));
                if (t != null) p.addPotionEffect(new PotionEffect(t, 60, e.getValue() - 1, true, false, true));
            }
            Integer rec = passiveCE(p).get("recover");
            if (rec != null) {
                AttributeInstance ai = p.getAttribute(attr("max_health"));
                double max = ai != null ? ai.getValue() : 20.0;
                if (p.getHealth() < max) p.setHealth(Math.min(max, p.getHealth() + 0.5 * rec));
            }
        }
        if (++scanCounter % 5 == 0 && getConfig().getBoolean("security.dupe-scan", true)) scanDupes();
    }
    private int scanCounter = 0;

    // ───────────────────────── кастомні зачарування в дії ─────────────────────────

    private Map<Material, Material> smelts() {
        if (smeltMap == null) {
            smeltMap = new HashMap<>();
            Iterator<Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext()) {
                Recipe r = it.next();
                if (!(r instanceof FurnaceRecipe fr)) continue;
                RecipeChoice rc = fr.getInputChoice();
                if (rc instanceof RecipeChoice.MaterialChoice mc) {
                    for (Material mm : mc.getChoices()) smeltMap.putIfAbsent(mm, fr.getResult().getType());
                } else {
                    smeltMap.putIfAbsent(fr.getInput().getType(), fr.getResult().getType());
                }
            }
        }
        return smeltMap;
    }

    private boolean canBreak(Block b) {
        Material m = b.getType();
        if (m.isAir() || m.getHardness() < 0 || b.isLiquid()) return false;
        if (b.getState(false) instanceof Container) return false;   // не ламаємо скрині/печі, щоб не губити вміст
        return m != Material.SPAWNER;
    }

    private boolean isOre(Material m) {
        String n = m.name();
        return n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS");
    }

    private String oreKey(Material m) { return m.name().replace("DEEPSLATE_", ""); }

    private void flood(Set<Block> out, Block start, Predicate<Block> ok, int limit, boolean diag) {
        Deque<Block> q = new ArrayDeque<>();
        Set<Block> seen = new HashSet<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty() && out.size() < limit) {
            Block b = q.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                if (!diag && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue;
                Block n = b.getRelative(dx, dy, dz);
                if (!seen.add(n)) continue;
                if (ok.test(n)) {
                    out.add(n);
                    q.add(n);
                    if (out.size() >= limit) return;
                }
            }
        }
    }

    /** Яка грань блока була під курсором — від неї залежить площина області. */
    private BlockFace faceOf(Player p, Block o) {
        RayTraceResult r = p.rayTraceBlocks(7.0);
        if (r != null && r.getHitBlockFace() != null && o.equals(r.getHitBlock())) return r.getHitBlockFace();
        float pitch = p.getLocation().getPitch();
        if (pitch > 45) return BlockFace.UP;
        if (pitch < -45) return BlockFace.DOWN;
        return p.getFacing().getOppositeFace();
    }

    /** {ширина, висота, глибина} для рівня молота (I і II), налаштовується в config.yml. */
    private int[] hammerDims(int lvl) {
        int l = lvl >= 2 ? 2 : 1;
        int def = l == 2 ? 10 : 3;
        String b = "hammer.levels." + l + ".";
        return new int[]{Math.max(1, getConfig().getInt(b + "width", def)),
                Math.max(1, getConfig().getInt(b + "height", def)),
                Math.max(1, getConfig().getInt(b + "depth", def))};
    }

    /**
     * Область молота: ширина x висота x глибина. Глибина йде в блок (вперед), висота - вгору
     * (1 блок нижче того, що ламаєш, решта вгору), ширина центрована. На підлогу/стелю - квадрат ширина x висота.
     */
    private void addArea(Set<Block> out, Block o, Player p, int lvl) {
        int[] dim = hammerDims(lvl);
        int w = dim[0], h = dim[1], dp = dim[2];
        int max = getConfig().getInt("hammer.max-blocks", 1500);
        BlockFace f = faceOf(p, o);
        BlockFace in = f.getOppositeFace();
        boolean flat = f == BlockFace.UP || f == BlockFace.DOWN;
        boolean sphere = "sphere".equalsIgnoreCase(getConfig().getString("hammer.shape", "cube"));
        int wlo = -((w - 1) / 2), whi = w / 2;
        int hlo = flat ? -((h - 1) / 2) : -1, hhi = flat ? h / 2 : h - 2;
        for (int d = 0; d < dp; d++) for (int a = wlo; a <= whi; a++) for (int b = hlo; b <= hhi; b++) {
            int x = in.getModX() * d, y = in.getModY() * d, z = in.getModZ() * d;
            if (flat) { x += a; z += b; }
            else {
                y += b;
                if (f == BlockFace.NORTH || f == BlockFace.SOUTH) x += a; else z += a;
            }
            if (sphere) {
                double na = (a - (wlo + whi) / 2.0) / (w / 2.0), nb = (b - (hlo + hhi) / 2.0) / (h / 2.0), nd = (d - (dp - 1) / 2.0) / (dp / 2.0);
                if (na * na + nb * nb + nd * nd > 1.0) continue;
            }
            out.add(o.getRelative(x, y, z));
            if (out.size() > max) return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (breaking) return;
        Player p = e.getPlayer();
        Block origin = e.getBlock();

        // кастомний TNT при ламанні повертає свій предмет
        if (origin.getType() == Material.TNT) {
            String raw = tntBlocks.remove(lkey(origin.getLocation()));
            if (raw != null) {
                tntDirty = true;
                e.setDropItems(false);
                if (p.getGameMode() != GameMode.CREATIVE)
                    origin.getWorld().dropItemNaturally(origin.getLocation().add(0.5, 0.5, 0.5), makeTnt(parseTnt(raw)));
                return;
            }
        }

        ItemStack tool = p.getInventory().getItemInMainHand();
        Map<String, Integer> ce = getCE(tool);
        if (ce.isEmpty()) return;
        boolean smelt = ce.containsKey("smelt"), magnet = ce.containsKey("magnet");
        Set<Block> targets = new LinkedHashSet<>();
        targets.add(origin);
        if (ce.containsKey("hammer") && !hammering.contains(p.getUniqueId())) addArea(targets, origin, p, ce.get("hammer"));
        if (ce.containsKey("vein") && p.isSneaking() && isOre(origin.getType())) {
            String key = oreKey(origin.getType());
            flood(targets, origin, b -> isOre(b.getType()) && oreKey(b.getType()).equals(key), 32, false);
        }
        if (ce.containsKey("timber") && Tag.LOGS.isTagged(origin.getType())) {
            flood(targets, origin, b -> Tag.LOGS.isTagged(b.getType()), 96, true);
        }
        if (targets.size() == 1 && !smelt && !magnet) return;

        e.setDropItems(false);
        HammerJob job = new HammerJob(p, tool, origin, targets, smelt, magnet);
        long budget = Math.max(5L, getConfig().getLong("hammer.ms-per-tick", 20L)) * 1_000_000L;
        if (job.step(System.nanoTime() + budget)) { job.finish(); return; }
        // велика область: решту ламаємо чергою по кілька мс за тік, щоб сервер не фризив
        hammering.add(p.getUniqueId());
        BukkitRunnable run = new BukkitRunnable() {
            @Override public void run() {
                if (job.step(System.nanoTime() + budget)) {
                    cancel(); blasts.remove(this); hammering.remove(p.getUniqueId());
                    job.finish();
                }
            }
            @Override public synchronized void cancel() { hammering.remove(p.getUniqueId()); super.cancel(); }
        };
        blasts.add(run);
        run.runTaskTimer(this, 1L, 1L);
    }

    /** Руйнування області молота порціями: step() ламає блоки до дедлайну, finish() віддає дроп і знімає міцність. */
    private class HammerJob {
        final Player p; final ItemStack tool; final Block origin; final boolean smelt, magnet;
        final Iterator<Block> it; final List<ItemStack> drops = new ArrayList<>();
        int extra = 0;

        HammerJob(Player p, ItemStack tool, Block origin, Set<Block> targets, boolean smelt, boolean magnet) {
            this.p = p; this.tool = tool; this.origin = origin; this.smelt = smelt; this.magnet = magnet;
            drops.addAll(origin.getDrops(tool, p));
            this.it = new ArrayList<>(targets).iterator();
        }

        /** true - усе зламано. */
        boolean step(long deadline) {
            while (it.hasNext()) {
                if (System.nanoTime() > deadline) return false;
                Block b = it.next();
                if (b.equals(origin)) continue;
                if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) continue;
                if (!canBreak(b)) continue;
                if (!p.isOnline()) return true;
                BlockBreakEvent ev = new BlockBreakEvent(b, p);
                breaking = true;
                try { Bukkit.getPluginManager().callEvent(ev); } finally { breaking = false; }
                if (ev.isCancelled()) continue;
                if (b.getType() == Material.TNT && tntBlocks.containsKey(lkey(b.getLocation()))) {
                    drops.add(makeTnt(parseTnt(tntBlocks.remove(lkey(b.getLocation())))));
                    tntDirty = true;
                } else if (ev.isDropItems()) {
                    drops.addAll(b.getDrops(tool, p));
                }
                b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 10, 0.25, 0.25, 0.25, b.getBlockData());
                b.setType(Material.AIR);
                extra++;
            }
            return true;
        }

        void finish() {
            if (extra > 0) origin.getWorld().playSound(origin.getLocation(), Sound.BLOCK_STONE_BREAK, 1f, 0.9f);
            Location at = origin.getLocation().add(0.5, 0.5, 0.5);
            List<ItemStack> outs = new ArrayList<>();
            for (ItemStack d : drops) {
                ItemStack out = d;
                if (smelt) {
                    Material r = smelts().get(d.getType());
                    if (r != null) out = new ItemStack(r, d.getAmount());
                }
                outs.add(out);
            }
            for (ItemStack out : mergeStacks(outs)) {
                if (magnet && p.isOnline()) {
                    for (ItemStack left : p.getInventory().addItem(out).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                } else {
                    origin.getWorld().dropItemNaturally(at, out);
                }
            }
            if (extra > 0 && p.isOnline() && p.getInventory().getItemInMainHand().getType() == tool.getType())
                damageTool(p, tool, Math.max(1, (int) Math.round(extra * getConfig().getDouble("hammer.durability-per-block", 0.1))));
        }
    }

    private List<ItemStack> mergeStacks(List<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack st : in) {
            if (st == null || st.getType().isAir()) continue;
            int left = st.getAmount();
            for (ItemStack o : out) {
                if (left <= 0) break;
                if (!o.isSimilar(st)) continue;
                int add = Math.min(o.getMaxStackSize() - o.getAmount(), left);
                if (add > 0) { o.setAmount(o.getAmount() + add); left -= add; }
            }
            while (left > 0) {
                ItemStack n = st.clone();
                int amt = Math.min(n.getMaxStackSize(), left);
                n.setAmount(amt);
                out.add(n);
                left -= amt;
            }
        }
        return out;
    }

    private void damageTool(Player p, ItemStack tool, int amount) {
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ItemMeta m = tool.getItemMeta();
        if (!(m instanceof Damageable d)) return;
        int max = tool.getType().getMaxDurability();
        if (max <= 0) return;
        int nd = d.getDamage() + amount;
        if (nd >= max) {
            p.getInventory().setItemInMainHand(null);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
        } else {
            d.setDamage(nd);
            tool.setItemMeta(d);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity victim)) return;
        Map<String, Integer> ce = getCE(p.getInventory().getItemInMainHand());
        if (ce.isEmpty()) return;
        Integer vamp = ce.get("vampire");
        if (vamp != null) {
            AttributeInstance ai = p.getAttribute(attr("max_health"));
            double max = ai != null ? ai.getValue() : 20.0;
            p.setHealth(Math.min(max, p.getHealth() + e.getFinalDamage() * 0.08 * vamp));
        }
        Integer lg = ce.get("lightning");
        if (lg != null && rnd.nextDouble() < 0.12 * lg && useCooldown(p, "Блискавка", getConfig().getDouble("cooldowns.lightning", 3.0))) {
            victim.getWorld().strikeLightningEffect(victim.getLocation());
            double dmg = 4.0 * lg;
            Bukkit.getScheduler().runTask(this, () -> { if (victim.isValid()) victim.damage(dmg); });
        }
    }

    /** Талісман у лівій руці: захист і шипи. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Map<String, Integer> ce = passiveCE(p);
        if (ce.isEmpty()) return;
        Integer g = ce.get("guard");
        if (g != null) e.setDamage(e.getDamage() * Math.max(0.1, 1 - 0.06 * g));
        Integer r = ce.get("reflect");
        if (r != null && e instanceof EntityDamageByEntityEvent be && be.getDamager() instanceof LivingEntity att && att != p) {
            double dmg = e.getDamage() * 0.1 * r;
            Bukkit.getScheduler().runTask(this, () -> { if (att.isValid()) att.damage(dmg); });
        }
    }

    // ───────────────────────── кліки ─────────────────────────

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (tooFast(p)) return;
        int slot = e.getRawSlot();
        ClickType ct = e.getClick();
        Bukkit.getScheduler().runTask(this, () -> handle(p, h, slot, ct));
    }

    private int delta(ClickType ct, int small, int big) {
        return switch (ct) {
            case LEFT -> small;
            case RIGHT -> -small;
            case SHIFT_LEFT -> big;
            case SHIFT_RIGHT -> -big;
            default -> 0;
        };
    }

    private double deltaD(ClickType ct, double small, double big) {
        return switch (ct) {
            case LEFT -> small;
            case RIGHT -> -small;
            case SHIFT_LEFT -> big;
            case SHIFT_RIGHT -> -big;
            default -> 0;
        };
    }

    private void handle(Player p, Holder h, int slot, ClickType ct) {
        Session s = ses(p);
        switch (h.menu) {
            case MAIN -> {
                switch (slot) {
                    case 10 -> openList(p, Cat.WEAPON, 0);
                    case 11 -> openList(p, Cat.ARMOR, 0);
                    case 12 -> openList(p, Cat.TOOL, 0);
                    case 13 -> startDirect(p, Cat.SPHERE);
                    case 14 -> startDirect(p, Cat.TALISMAN);
                    case 15 -> startDirect(p, Cat.POTION);
                    case 16 -> startDirect(p, Cat.TNT);
                    case 22 -> openBookCat(p);
                    default -> { }
                }
            }
            case LIST -> {
                if (slot == 49) { openMain(p); return; }
                if (slot == 45 && h.page > 0) { openList(p, s.cat, h.page - 1); return; }
                if (slot == 53) { openList(p, s.cat, h.page + 1); return; }
                if (slot < 45) {
                    List<Material> list = materials(s.cat);
                    int idx = h.page * 45 + slot;
                    if (idx < list.size() && list.get(idx) != null) {
                        s.base = list.get(idx);
                        s.item = newItem(s.cat, s.base, s);
                        s.ns = null;
                        openEditor(p);
                    }
                }
            }
            case EDITOR -> {
                boolean g = gear(s.cat);
                switch (slot) {
                    case 29 -> {
                        if (g) openEnch(p, 0);
                        else if (s.cat == Cat.SPHERE) openLook(p, 0);
                        else if (s.cat == Cat.POTION) { cyclePotion(s); openEditor(p); }
                        else if (s.cat == Cat.TALISMAN) openCEnch(p);
                    }
                    case 30 -> {
                        if (g) openCEnch(p);
                        else if (s.cat == Cat.POTION) askInput(p, s, "pcolor", null, "Введи колір зілля у форматі #rrggbb:");
                    }
                    case 31 -> {
                        if (s.cat == Cat.POTION) openPEffect(p);
                        else if (s.cat == Cat.TNT) openTnt(p);
                        else openAttr(p);
                    }
                    case 33 -> { if (s.cat == Cat.SPHERE || s.cat == Cat.TALISMAN) openEffect(p); }
                    case 38 -> openName(p);
                    case 40 -> { p.getInventory().addItem(stamp(s.item.clone())); p.sendMessage(plain("&aПредмет видано.")); }
                    case 42 -> { s.item = newItem(s.cat, s.base != null ? s.base : s.item.getType(), s); s.ns = null; openEditor(p); }
                    case 49 -> { if (direct(s.cat)) openMain(p); else openList(p, s.cat, 0); }
                    default -> { }
                }
            }
            case ENCH -> {
                if (slot == 49) { openEditor(p); return; }
                if (slot == 45 && h.page > 0) { openEnch(p, h.page - 1); return; }
                if (slot == 53) { openEnch(p, h.page + 1); return; }
                if (slot >= 45) return;
                List<Enchantment> list = enchs(s.item);
                int idx = h.page * 45 + slot;
                if (idx >= list.size()) return;
                Enchantment en = list.get(idx);
                if (ct == ClickType.MIDDLE) {
                    askInput(p, s, "ench", en.getKey().toString(), "Введи рівень (число або римською, 0 = прибрати):");
                    return;
                }
                int d = delta(ct, 1, 5);
                if (d == 0) return;
                setEnch(s.item, en, s.item.getEnchantmentLevel(en) + d);
                openEnch(p, h.page);
            }
            case CENCH -> {
                if (slot == 31) { openEditor(p); return; }
                List<CE> list = applicable(s.item);
                if (slot >= list.size()) return;
                CE ce = list.get(slot);
                if (ct == ClickType.MIDDLE) {
                    askInput(p, s, "cench", ce.id, "Введи рівень «" + ce.name + "» (число або римською, 0 = прибрати, макс. " + ce.max + "):");
                    return;
                }
                int d = delta(ct, 1, 1);
                if (d == 0) return;
                setCE(s.item, ce.id, getCE(s.item).getOrDefault(ce.id, 0) + d);
                openCEnch(p);
            }
            case ATTR -> {
                if (slot == 22) { openEditor(p); return; }
                if (slot >= ATTRS.length) return;
                String key = (String) ATTRS[slot][0];
                double step = (double) ATTRS[slot][3];
                if (ct == ClickType.MIDDLE) {
                    askInput(p, s, "attr", key, "Введи значення (наприклад 1.5 або 0.5; 0 = прибрати):");
                    return;
                }
                double d = deltaD(ct, step, step / 2);
                if (d == 0) return;
                setAttr(s.item, key, getAttr(s.item, key) + d);
                openAttr(p);
            }
            case LOOK -> {
                if (slot == 49) { openEditor(p); return; }
                if (slot == 45 && h.page > 0) { openLook(p, h.page - 1); return; }
                if (slot == 53) { openLook(p, h.page + 1); return; }
                if (slot >= 45) return;
                int idx = h.page * 45 + slot;
                if (idx < heads.size()) { s.item = applyHead(s.item, heads.get(idx)[1]); openEditor(p); }
            }
            case EFFECT -> {
                if (slot == 31) { openEditor(p); return; }
                if (slot == 27) { s.colorMode = !s.colorMode; openEffect(p); return; }
                if (slot >= EFFECTS.length) return;
                String key = (String) EFFECTS[slot][0];
                if (s.colorMode) {
                    if (getEffects(s.item).getOrDefault(key, 0) <= 0) { p.sendMessage(plain("&eСпочатку додай цей ефект (вимкни режим кольору).")); return; }
                    openColor(p, "eff:" + key);
                    return;
                }
                if (ct == ClickType.MIDDLE) {
                    askInput(p, s, "effect", key, "Введи рівень (число або римською, 0 = прибрати):");
                    return;
                }
                int d = delta(ct, 1, 5);
                if (d == 0) return;
                setEffect(s.item, key, getEffects(s.item).getOrDefault(key, 0) + d);
                openEffect(p);
            }
            case PEFFECT -> {
                if (slot == 31) { openEditor(p); return; }
                if (slot >= PEFFECTS.length) return;
                String key = (String) PEFFECTS[slot][0];
                double[] cur = potionEff(s.item, key);
                if (ct == ClickType.MIDDLE) {
                    askInput(p, s, "pef", key, "Введи: рівень тривалість_у_секундах (наприклад: 3 600 або 2 0.5; рівень 0 = прибрати):");
                    return;
                }
                int lvl = (int) cur[0];
                double sec = cur[1] == 0 ? 180 : cur[1];
                switch (ct) {
                    case LEFT -> lvl += 1;
                    case RIGHT -> lvl -= 1;
                    case SHIFT_LEFT -> sec += 30;
                    case SHIFT_RIGHT -> sec = Math.max(5, sec - 30);
                    default -> { return; }
                }
                if (cur[0] == 0 && (ct == ClickType.SHIFT_LEFT || ct == ClickType.SHIFT_RIGHT)) lvl = 1;
                setPotionEff(s.item, key, Math.max(0, lvl), sec);
                openPEffect(p);
            }
            case TNT -> {
                if (slot == 22) { openEditor(p); return; }
                double[] d = getTnt(s.item);
                if (slot == 10) {
                    if (ct == ClickType.MIDDLE) { askInput(p, s, "tntp", null, "Введи силу вибуху (будь-яке число, можна 2.5):"); return; }
                    d[0] = clampPower(d[0] + deltaD(ct, 1, 10));
                } else if (slot == 11) {
                    if (ct == ClickType.MIDDLE) { askInput(p, s, "tntf", null, "Введи запал у секундах (0.25–30, можна 1.5):"); return; }
                    d[1] = Math.max(5, Math.min(600, d[1] + deltaD(ct, 10, 40)));
                } else if (slot >= 12 && slot < 12 + TNT_PRESETS.length) {
                    d[0] = clampPower(TNT_PRESETS[slot - 12]);
                } else return;
                setTnt(s.item, d);
                openTnt(p);
            }
            case NAME -> {
                NameStyle ns = nameStyle(s);
                switch (slot) {
                    case 49 -> { openEditor(p); return; }
                    case 10 -> { openColor(p, "name"); return; }
                    case 11 -> { openGrad(p); return; }
                    case 12 -> { s.colorTarget = "name"; askInput(p, s, "namehex", null, "Введи колір у форматі #rrggbb:"); return; }
                    case 13 -> { askInput(p, s, "namegrad", null, "Введи від 2 до 6 кольорів через пробіл (наприклад #ff0000 #ffff00 #00ff00):"); return; }
                    case 14 -> ns.cols = new ArrayList<>();
                    case 19 -> ns.b = !ns.b;
                    case 20 -> ns.i = !ns.i;
                    case 21 -> ns.u = !ns.u;
                    case 22 -> ns.st = !ns.st;
                    case 23 -> ns.ob = !ns.ob;
                    case 28, 29, 30, 31 -> ns.font = slot - 28;
                    case 33 -> { openStyle(p); return; }
                    case 35 -> { askInput(p, s, "nametext", null, "Введи текст назви:"); return; }
                    default -> { return; }
                }
                applyName(s);
                openName(p);
            }
            case NCOLOR -> {
                if (slot == 49) {
                    if (s.colorTarget != null && s.colorTarget.startsWith("eff:")) openEffect(p); else openName(p);
                    return;
                }
                if (slot == 48) { askInput(p, s, "colorhex", null, "Введи колір у форматі #rrggbb:"); return; }
                if (slot < PALETTE.length && slot < 45) applyColor(p, s, PALETTE[slot][0].toLowerCase());
            }
            case NGRAD -> {
                if (slot == 49) { openName(p); return; }
                if (slot == 48) { askInput(p, s, "namegrad", null, "Введи від 2 до 6 кольорів через пробіл (наприклад #ff0000 #ffff00 #00ff00):"); return; }
                if (slot < GRADS.length && slot < 45) {
                    NameStyle ns = nameStyle(s);
                    ns.cols = new ArrayList<>(Arrays.asList(GRADS[slot]).subList(1, GRADS[slot].length));
                    applyName(s);
                    openName(p);
                }
            }
            case NSTYLE -> {
                if (slot == 31) { openName(p); return; }
                if (slot < UNI_NAMES.length && slot < 27) {
                    nameStyle(s).uni = slot;
                    applyName(s);
                    openName(p);
                }
            }
            case BOOKCAT -> {
                if (slot == 31) { openMain(p); return; }
                if (slot < BCATS.length) { s.bookCat = slot; openBooks(p, 0); }
            }
            case BOOKS -> {
                if (slot == 49) { openBookCat(p); return; }
                if (slot == 45 && h.page > 0) { openBooks(p, h.page - 1); return; }
                if (slot == 53) { openBooks(p, h.page + 1); return; }
                if (slot >= 45) return;
                List<Object> list = bookEntries(s.bookCat);
                int idx = h.page * 45 + slot;
                if (idx >= list.size()) return;
                Object o = list.get(idx);
                int max = o instanceof Enchantment en ? en.getMaxLevel() : ((CE) o).max;
                if (ct == ClickType.MIDDLE) {
                    String key = o instanceof Enchantment en ? "e:" + en.getKey() : "c:" + ((CE) o).id;
                    askInput(p, s, "booklvl", key, "Введи рівень книги (число або римською):");
                    return;
                }
                giveBook(p, o, (ct == ClickType.RIGHT || ct == ClickType.SHIFT_RIGHT) ? max : 1);
            }
        }
    }

    // ───────────────────────── ввід через чат ─────────────────────────

    private void askInput(Player p, Session s, String kind, String key, String msg) {
        s.pendingKind = kind;
        s.pendingKey = key;
        p.closeInventory();
        p.sendMessage(plain("&e" + msg));
        p.sendMessage(Component.text("[✖ Скасувати]", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/forge cancel"))
                .append(Component.text("   або напиши  -", NamedTextColor.GRAY)));
    }

    private boolean isCancel(String msg) {
        String t = msg.trim().toLowerCase();
        return t.equals("-") || t.equals(".") || t.equals("скасувати") || t.equals("cancel") || t.equals("відміна");
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.pendingKind == null) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> handleInput(p, s, msg));
    }

    private void handleInput(Player p, Session s, String msg) {
        String kind = s.pendingKind, key = s.pendingKey;
        s.pendingKind = null;
        s.pendingKey = null;
        boolean cancel = isCancel(msg);
        switch (kind) {
            case "nametext" -> {
                if (!cancel && !msg.isBlank()) { nameStyle(s).text = msg; applyName(s); }
                openName(p);
            }
            case "namehex" -> {
                String hx = cancel ? null : hex(msg);
                if (!cancel && hx == null) p.sendMessage(plain("&cНевірний колір. Приклад: #ff8800"));
                if (hx != null) { nameStyle(s).cols = new ArrayList<>(List.of(hx)); applyName(s); }
                openName(p);
            }
            case "colorhex" -> {
                String hx = cancel ? null : hex(msg);
                if (!cancel && hx == null) p.sendMessage(plain("&cНевірний колір. Приклад: #ff8800"));
                if (hx != null) { applyColor(p, s, hx); return; }
                if (s.colorTarget != null && s.colorTarget.startsWith("eff:")) openEffect(p); else openName(p);
            }
            case "namegrad" -> {
                List<String> cols = new ArrayList<>();
                if (!cancel) for (String part : msg.trim().split("\\s+")) { String hx = hex(part); if (hx != null) cols.add(hx); }
                if (!cancel && cols.size() < 2) p.sendMessage(plain("&cПотрібно мінімум 2 кольори. Приклад: #ff0000 #0000ff"));
                if (cols.size() >= 2) { nameStyle(s).cols = new ArrayList<>(cols.subList(0, Math.min(6, cols.size()))); applyName(s); }
                openName(p);
            }
            case "pcolor" -> {
                String hx = cancel ? null : hex(msg);
                if (!cancel && hx == null) p.sendMessage(plain("&cНевірний колір. Приклад: #ff8800"));
                if (hx != null && s.item.getItemMeta() instanceof PotionMeta pm) {
                    TextColor tc = TextColor.fromHexString(hx);
                    pm.setColor(Color.fromRGB(tc.red(), tc.green(), tc.blue()));
                    s.item.setItemMeta(pm);
                }
                openEditor(p);
            }
            case "pef" -> {
                if (!cancel) {
                    String[] parts = msg.trim().split("\\s+");
                    Integer lvl = parseLevel(parts[0]);
                    Double sec = parts.length > 1 ? parseDouble(parts[1]) : Double.valueOf(180);
                    if (lvl == null || sec == null) p.sendMessage(plain("&cФормат: рівень тривалість_с (напр. 3 600)"));
                    else setPotionEff(s.item, key, Math.max(0, lvl), Math.max(0.05, sec));
                }
                openPEffect(p);
            }
            case "tntp" -> {
                Double v = cancel ? null : parseDouble(msg);
                if (!cancel && v == null) p.sendMessage(plain("&cНе число."));
                if (v != null) { double[] d = getTnt(s.item); d[0] = clampPower(v); setTnt(s.item, d); }
                openTnt(p);
            }
            case "tntf" -> {
                Double v = cancel ? null : parseDouble(msg);
                if (!cancel && v == null) p.sendMessage(plain("&cНе число."));
                if (v != null) { double[] d = getTnt(s.item); d[1] = Math.max(5, Math.min(600, Math.round(v * 20))); setTnt(s.item, d); }
                openTnt(p);
            }
            case "cench" -> {
                Integer lvl = cancel ? null : parseLevel(msg);
                if (!cancel && lvl == null) p.sendMessage(plain("&cНе число."));
                if (lvl != null && CES.containsKey(key)) setCE(s.item, key, Math.max(0, lvl));
                openCEnch(p);
            }
            case "booklvl" -> {
                Integer lvl = cancel ? null : parseLevel(msg);
                if (!cancel && lvl == null) p.sendMessage(plain("&cНе число."));
                if (lvl != null && key != null) {
                    if (key.startsWith("e:")) {
                        Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.fromString(key.substring(2)));
                        if (en != null) giveBook(p, en, lvl);
                    } else {
                        CE ce = CES.get(key.substring(2));
                        if (ce != null) giveBook(p, ce, lvl);
                    }
                }
                openBooks(p, 0);
            }
            case "ench" -> {
                Integer lvl = cancel ? null : parseLevel(msg);
                if (!cancel && lvl == null) p.sendMessage(plain("&cНе число."));
                if (lvl != null) {
                    Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.fromString(key));
                    if (en != null) setEnch(s.item, en, lvl);
                }
                openEnch(p, 0);
            }
            case "attr" -> {
                Double v = cancel ? null : parseDouble(msg);
                if (!cancel && v == null) p.sendMessage(plain("&cНе число."));
                if (v != null) setAttr(s.item, key, v);
                openAttr(p);
            }
            case "effect" -> {
                Integer lvl = cancel ? null : parseLevel(msg);
                if (!cancel && lvl == null) p.sendMessage(plain("&cНе число."));
                if (lvl != null) setEffect(s.item, key, lvl);
                openEffect(p);
            }
            default -> openEditor(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        sessions.remove(id); lastClick.remove(id); cooldowns.remove(id); hammering.remove(id);
    }
}
