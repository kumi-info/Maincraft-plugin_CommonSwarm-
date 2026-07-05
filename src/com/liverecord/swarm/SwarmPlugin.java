package com.liverecord.swarm;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 汎用 攻撃モブ召喚プラグイン。
 *   /swarm <モブ> [攻撃回数] [匹数] [プレイヤー名|all|random]
 *   例) /swarm bee 2 1  … 蜂を1匹、2回攻撃で消滅
 *   /swarm reset … 召喚した攻撃モブを全消去（テスト用）
 * 召喚されたモブは対象プレイヤーを攻撃する。設定/引数の「攻撃回数」だけ攻撃すると消滅する。
 * 召喚モブは PDC でタグ付けし、攻撃のたびに残り回数を減らす。
 */
public final class SwarmPlugin extends JavaPlugin implements Listener {

    /** alias(小文字) → EntityType。設定 mobs から読み込む。 */
    private final Map<String, EntityType> mobAliases = new LinkedHashMap<>();

    private String defaultMob;
    private int defaultCount;
    private int angerTicks;
    private double spawnRadius;
    private double spawnHeight;
    private boolean persistent;
    private int maxCount;
    private int defaultAttackCount;

    /** 召喚モブの目印タグ（値は使わず存在チェックのみ）。 */
    private NamespacedKey tagKey;
    /** このモブを消すのに必要な被弾しきい値（=召喚時の攻撃回数）。被弾累計がこの値に達するたびに1匹消す。 */
    private NamespacedKey remainKey;

    /** 対象プレイヤーごとの「swarmモブからの被弾」累計カウンタ（しきい値ごとに1匹消す）。 */
    private final Map<UUID, Integer> hitAccumulator = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getResource("README.md") != null) {
            saveResource("README.md", true); // plugins/Swarm/README.md を毎回最新化
        }
        tagKey = new NamespacedKey(this, "swarm");
        remainKey = new NamespacedKey(this, "swarm_remaining");
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Swarm 有効化。/swarm が利用可能。");
    }

    private void loadSettings() {
        defaultCount = Math.max(1, getConfig().getInt("count", 1));
        angerTicks = Math.max(1, getConfig().getInt("anger-ticks", 99999));
        spawnRadius = Math.max(0.0, getConfig().getDouble("spawn-radius", 3.0));
        spawnHeight = getConfig().getDouble("spawn-height", 1.5);
        persistent = getConfig().getBoolean("persistent", true);
        maxCount = Math.max(1, getConfig().getInt("max-count", 100));
        defaultAttackCount = Math.max(1, getConfig().getInt("attack-count", 1));
        defaultMob = getConfig().getString("default-mob", "bee").toLowerCase(Locale.ROOT);

        mobAliases.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("mobs");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                EntityType type = parseMobType(sec.getString(key));
                if (type != null) {
                    mobAliases.put(key.toLowerCase(Locale.ROOT), type);
                }
            }
        }
        if (mobAliases.isEmpty()) {
            putDefault("bee", EntityType.BEE);
            putDefault("zombie", EntityType.ZOMBIE);
            putDefault("skeleton", EntityType.SKELETON);
            putDefault("spider", EntityType.SPIDER);
            putDefault("blaze", EntityType.BLAZE);
            putDefault("vex", EntityType.VEX);
            putDefault("wolf", EntityType.WOLF);
            putDefault("creeper", EntityType.CREEPER);
        }
    }

    private void putDefault(String alias, EntityType type) {
        mobAliases.put(alias, type);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("swarm")) {
            return false;
        }
        if (!sender.hasPermission("swarm.use")) {
            sender.sendMessage("§cこのコマンドを使う権限がありません。");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadSettings();
            sender.sendMessage("§aconfig.yml を再読み込みしました。");
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("clear"))) {
            resetSwarm();
            sender.sendMessage("§aモブを消去しました。");
            return true;
        }

        // 第1引数: モブ（省略時は default-mob）。
        String mobArg = (args.length >= 1) ? args[0] : defaultMob;
        EntityType type = resolveMob(mobArg);
        if (type == null) {
            sender.sendMessage("§c不明なモブ: §e" + mobArg + "§c。指定可能: §f" + String.join(", ", mobAliases.keySet()));
            sender.sendMessage("§7※ config.yml の mobs に未登録でも、有効な EntityType 名（例: phantom）なら指定できます。");
            return true;
        }

        // 第2引数: 攻撃回数（省略時は config の attack-count）。
        int attackCount = defaultAttackCount;
        if (args.length >= 2) {
            Integer parsed = tryParseInt(args[1]);
            if (parsed == null || parsed < 1) {
                sender.sendMessage("§c攻撃回数は 1 以上の整数で指定してください。 使い方: /swarm <モブ> [攻撃回数] [匹数] [対象]");
                return true;
            }
            attackCount = parsed;
        }

        // 第3引数: 匹数（省略時は config の count）。
        int count = defaultCount;
        if (args.length >= 3) {
            Integer parsed = tryParseInt(args[2]);
            if (parsed == null || parsed < 1) {
                sender.sendMessage("§c匹数は 1 以上の整数で指定してください。 使い方: /swarm <モブ> [攻撃回数] [匹数] [対象]");
                return true;
            }
            count = Math.min(maxCount, parsed);
        }

        // 第4引数: 対象（省略時は実行者自身）。
        List<Player> targets = resolveTargets(sender, (args.length >= 4) ? args[3] : null);
        if (targets == null) {
            return true; // エラー応答済み。
        }
        if (targets.isEmpty()) {
            sender.sendMessage("§c対象となるプレイヤーが見つかりませんでした。");
            return true;
        }

        int total = 0;
        for (Player target : targets) {
            total += summonMobs(type, target, count, attackCount);
        }
        String mobName = type.name().toLowerCase(Locale.ROOT);
        if (targets.size() == 1) {
            sender.sendMessage("§e" + targets.get(0).getName() + " §6に攻撃モブ §c" + mobName + " ×" + total
                    + "§6 を召喚（§e" + attackCount + "回§6攻撃で消滅）！");
        } else {
            sender.sendMessage("§6攻撃モブ §c" + mobName + " ×" + total + "§6 を §e" + targets.size()
                    + " 人§6 に召喚（§e" + attackCount + "回§6攻撃で消滅）！");
        }
        return true;
    }

    /** モブ名を EntityType に解決。エイリアス優先、無ければ EntityType 名として解釈。攻撃可能(Mob)でなければ null。 */
    private EntityType resolveMob(String name) {
        if (name == null) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT);
        EntityType alias = mobAliases.get(key);
        if (alias != null) {
            return alias;
        }
        return parseMobType(name);
    }

    /** 文字列を「攻撃可能な生物(Mob)」の EntityType として解釈する。該当しなければ null。 */
    private EntityType parseMobType(String name) {
        if (name == null) {
            return null;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        Class<? extends Entity> cls = type.getEntityClass();
        if (cls == null || !Mob.class.isAssignableFrom(cls)) {
            return null;
        }
        return type;
    }

    /** 第3引数から攻撃対象リストを決める。null を返した場合はエラー応答済み。 */
    private List<Player> resolveTargets(CommandSender sender, String arg) {
        List<Player> list = new ArrayList<>();
        if (arg == null) {
            if (sender instanceof Player) {
                list.add((Player) sender);
                return list;
            }
            sender.sendMessage("§cコンソールから実行する場合は対象を指定してください。 例: /swarm bee 1 5 <プレイヤー名|all|random>");
            return null;
        }
        String key = arg.toLowerCase(Locale.ROOT);
        if (key.equals("all") || key.equals("@a")) {
            list.addAll(Bukkit.getOnlinePlayers());
            return list;
        }
        if (key.equals("random") || key.equals("@r")) {
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (online.isEmpty()) {
                return list;
            }
            list.add(online.get(ThreadLocalRandom.current().nextInt(online.size())));
            return list;
        }
        Player p = Bukkit.getPlayerExact(arg);
        if (p == null) {
            sender.sendMessage("§cプレイヤー §e" + arg + " §c が見つかりません（オンラインのみ指定可）。");
            return null;
        }
        list.add(p);
        return list;
    }

    /** 対象プレイヤーの周囲に攻撃的なモブを count 体召喚（各 attackCount 回攻撃で消滅）し、その数を返す。 */
    private int summonMobs(EntityType type, Player target, int count, int attackCount) {
        Location base = target.getLocation();
        World world = base.getWorld();
        if (world == null) {
            return 0;
        }
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double r = (spawnRadius <= 0.0) ? 0.0
                    : spawnRadius * (0.4 + ThreadLocalRandom.current().nextDouble(0.6));
            Location loc = base.clone().add(Math.cos(angle) * r, spawnHeight, Math.sin(angle) * r);
            Entity entity = world.spawnEntity(loc, type);
            makeAggressive(entity, target);
            entity.setPersistent(persistent);
            if (entity instanceof Mob) {
                ((Mob) entity).setRemoveWhenFarAway(false);
            }
            // 召喚タグ＋「消すのに必要な被弾しきい値（=攻撃回数）」を刻む。
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            pdc.set(tagKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(remainKey, PersistentDataType.INTEGER, Math.max(1, attackCount));
            spawned++;
        }
        target.playSound(target.getLocation(), Sound.ENTITY_BEE_STING, 0.8f, 1.0f);
        target.sendMessage("§c⚠ 攻撃モブ " + type.name().toLowerCase(Locale.ROOT) + " " + spawned + " 体に襲われている！");
        return spawned;
    }

    /** モブを「対象を攻撃する」状態にする。中立モブ（蜂・狼）は怒り状態にする。 */
    private void makeAggressive(Entity entity, Player target) {
        if (entity instanceof Bee) {
            Bee bee = (Bee) entity;
            bee.setAnger(angerTicks);
            bee.setCannotEnterHiveTicks(angerTicks);
        }
        if (entity instanceof Wolf) {
            ((Wolf) entity).setAngry(true);
        }
        if (entity instanceof Mob) {
            ((Mob) entity).setTarget(target);
        }
    }

    /**
     * 召喚モブがプレイヤーを攻撃するたびに「被弾累計」を1増やし、
     * しきい値（=攻撃回数）に達するたびに swarm モブを 1 匹（攻撃してきた個体）消す。
     * 「同じ個体が N 回刺す」ではなく「対象が swarm から N 回被弾するごとに 1 匹消える」累計方式。
     * これにより 5 匹召喚（攻撃回数2）なら 2 回被弾ごとに 1 匹、延べ10回で全消しとなる。
     *
     * ignoreCancelled は付けない（クリエイティブ無敵や /barrier でダメージが無効化されても
     * 「接触＝攻撃判定」を数えて確実に消すため）。MONITOR でイベントは書き換えない。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwarmAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return; // プレイヤーへの攻撃のみカウント。
        }
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile) {
            ProjectileSource src = ((Projectile) attacker).getShooter();
            if (!(src instanceof Entity)) {
                return;
            }
            attacker = (Entity) src;
        }
        PersistentDataContainer pdc = attacker.getPersistentDataContainer();
        Integer threshold = pdc.get(remainKey, PersistentDataType.INTEGER);
        if (threshold == null) {
            return; // 召喚モブではない。
        }
        int t = Math.max(1, threshold);

        Player victim = (Player) event.getEntity();
        UUID uuid = victim.getUniqueId();
        int acc = hitAccumulator.getOrDefault(uuid, 0) + 1;

        if (acc >= t) {
            // しきい値到達: 攻撃してきた個体を 1 匹消す。余りは次に持ち越す。
            poof(attacker.getLocation());
            attacker.remove();
            hitAccumulator.put(uuid, acc - t);
            return;
        }

        // まだしきい値未満: カウントを保持し、攻撃を継続させる（蜂は刺し後の自滅を防ぐ）。
        hitAccumulator.put(uuid, acc);
        if (attacker instanceof Bee) {
            Bee bee = (Bee) attacker;
            bee.setHasStung(false);
            bee.setAnger(angerTicks);
            bee.setCannotEnterHiveTicks(angerTicks);
        }
        if (attacker instanceof Wolf) {
            ((Wolf) attacker).setAngry(true);
        }
        if (attacker instanceof Mob) {
            ((Mob) attacker).setTarget(victim);
        }
    }

    /**
     * 全ワールドから「プレイヤー以外」の全エンティティを消去し、消した数を返す。
     * バニラの {@code /kill @e[type=!player]} と同じ挙動（モブ・アイテム・矢・防具立て等すべて）。
     * 召喚モブのタグ有無に関わらず確実に消えるようにするための仕様。
     */
    private int resetSwarm() {
        int removed = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity en : new ArrayList<>(w.getEntities())) {
                if (en instanceof Player) {
                    continue; // プレイヤーは残す。
                }
                en.remove();
                removed++;
            }
        }
        hitAccumulator.clear(); // 被弾累計もリセット。
        return removed;
    }

    /** 消滅時の小さな煙＋音の演出。 */
    private void poof(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        loc.getWorld().spawnParticle(Particle.POOF, loc.clone().add(0, 0.4, 0), 16, 0.3, 0.3, 0.3, 0.02);
        loc.getWorld().playSound(loc, Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.8f);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("swarm")) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            List<String> opts = new ArrayList<>(mobAliases.keySet());
            opts.add("reset");
            opts.add("reload");
            for (String o : opts) {
                if (o.startsWith(p)) {
                    out.add(o);
                }
            }
            return out;
        }
        if (args.length == 2) {
            // 攻撃回数。
            String p = args[1].toLowerCase(Locale.ROOT);
            for (String s : new String[]{"1", "2", "3", "5"}) {
                if (s.startsWith(p)) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 3) {
            // 匹数。
            String p = args[2].toLowerCase(Locale.ROOT);
            for (String s : new String[]{"1", "5", "10", "20"}) {
                if (s.startsWith(p)) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 4) {
            // 対象。
            String p = args[3].toLowerCase(Locale.ROOT);
            List<String> opts = new ArrayList<>();
            opts.add("all");
            opts.add("random");
            for (Player pl : Bukkit.getOnlinePlayers()) {
                opts.add(pl.getName());
            }
            for (String o : opts) {
                if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                    out.add(o);
                }
            }
            return out;
        }
        return out;
    }

    private Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
