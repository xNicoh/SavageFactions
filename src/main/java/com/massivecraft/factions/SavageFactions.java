package com.massivecraft.factions;

import ch.njol.skript.Skript;
import ch.njol.skript.SkriptAddon;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.massivecraft.factions.cmd.CmdAutoHelp;
import com.massivecraft.factions.cmd.CommandContext;
import com.massivecraft.factions.cmd.FCmdRoot;
import com.massivecraft.factions.cmd.FCommand;
import com.massivecraft.factions.integration.Econ;
import com.massivecraft.factions.integration.Worldguard;
import com.massivecraft.factions.integration.dynmap.EngineDynmap;
import com.massivecraft.factions.listeners.*;
import com.massivecraft.factions.struct.ChatMode;
import com.massivecraft.factions.struct.Relation;
import com.massivecraft.factions.struct.Role;
import com.massivecraft.factions.util.*;
import com.massivecraft.factions.util.Particles.ReflectionUtils;
import com.massivecraft.factions.zcore.CommandVisibility;
import com.massivecraft.factions.zcore.MPlugin;
import com.massivecraft.factions.zcore.ffly.UtilFly;
import com.massivecraft.factions.zcore.ffly.flyparticledata.FlyParticleData;
import com.massivecraft.factions.zcore.fperms.Access;
import com.massivecraft.factions.zcore.fperms.Permissable;
import com.massivecraft.factions.zcore.fperms.PermissableAction;
import com.massivecraft.factions.zcore.fupgrades.UpgradeListener;
import com.massivecraft.factions.zcore.util.TextUtil;
import me.lucko.commodore.CommodoreProvider;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.*;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.stream.Collectors;


public class SavageFactions extends MPlugin {

    // Our single plugin instance.
    // Single 4 life.
    public static SavageFactions plugin;
    public static Permission perms = null;
    // This plugin sets the boolean true when fully enabled.
    // Plugins can check this boolean while hooking in have
    // a green light to use the api.
    public static boolean startupFinished = false;

    public boolean PlaceholderApi;
    // Commands
    public FCmdRoot cmdBase;
    public CmdAutoHelp cmdAutoHelp;
    public boolean mc17 = false;
    public boolean mc18 = false;
    public boolean mc113 = false;
    public boolean mc112 = false;
    public boolean mc114 = false;
    public boolean useNonPacketParticles = false;
    public boolean factionsFlight = false;
    public boolean spawnersPlacing = true;
    //multiversion material fields
    SkriptAddon skriptAddon;
    private boolean locked = false;
    private Integer AutoLeaveTask = null;
    private boolean hookedPlayervaults;
    private ClipPlaceholderAPIManager clipPlaceholderAPIManager;
    private boolean mvdwPlaceholderAPIManager = false;
    private Listener[] eventsListener;

    public SavageFactions() {
        plugin = this;
    }

    public boolean getLocked() {
        return this.locked;
    }

    public void setLocked(boolean val) {
        this.locked = val;
        this.setAutoSave(val);
    }

    public void playSoundForAll(String sound) {
        for (Player pl : Bukkit.getOnlinePlayers()) {
            playSound(pl, sound);
        }
    }

    public void playSoundForAll(List<String> sounds) {
        for (Player pl : Bukkit.getOnlinePlayers()) {
            playSound(pl, sounds);
        }
    }

    public void playSound(Player p, List<String> sounds) {
        for (String sound : sounds) {
            playSound(p, sound);
        }
    }

    public void playSound(Player p, String sound) {
        float pitch = Float.valueOf(sound.split(":")[1]);
        sound = sound.split(":")[0];
        p.playSound(p.getLocation(), Sound.valueOf(sound), pitch, 5.0F);
    }

    @Override
    public void onEnable() {
        log("==== Setup ====");


        // Vault dependency check.
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            log("Vault is not present, the plugin will not run properly.");
            getServer().getPluginManager().disablePlugin(plugin);
            return;
        }

        int version = Integer.parseInt(ReflectionUtils.PackageType.getServerVersion().split("_")[1]);
        switch (version) {
            case 7:
                SavageFactions.plugin.log("Minecraft Version 1.7 found, disabling banners, itemflags inside GUIs, and Titles.");
                mc17 = true;
                break;
            case 8:
                SavageFactions.plugin.log("Minecraft Version 1.8 found, Title Fadeouttime etc will not be configurable.");
                mc18 = true;
                break;
            case 12:
                mc112 = true;
                break;
            case 13:
                SavageFactions.plugin.log("Minecraft Version 1.13 found, New Items will be used.");
                mc113 = true;
                break;
            case 14:
                SavageFactions.plugin.log("Minecraft Version 1.14 found.");
                mc114 = true;
                break;
        }
        migrateFPlayerLeaders();
        log("==== End Setup ====");

        if (!preEnable()) {
            return;
        }

        new ConfigVersion(this.getConfig()).checkVersion();

        this.loadSuccessful = false;

        saveDefaultConfig();
        // Load Conf from disk
        Conf.load();
        com.massivecraft.factions.integration.Essentials.setup();
        hookedPlayervaults = setupPlayervaults();
        FPlayers.getInstance().load();
        Factions.getInstance().load();

        for (FPlayer fPlayer : FPlayers.getInstance().getAllFPlayers()) {
            Faction faction = Factions.getInstance().getFactionById(fPlayer.getFactionId());
            if (faction == null) {
                log("Invalid faction id on " + fPlayer.getName() + ":" + fPlayer.getFactionId());
                fPlayer.resetFactionData(false);
                continue;
            }
            faction.addFPlayer(fPlayer);
        }

        Factions.getInstance().getAllFactions().forEach(Faction::refreshFPlayers);


        UtilFly.run();

        Board.getInstance().load();
        Board.getInstance().clean();

        // Add Base Commands
        this.cmdBase = new FCmdRoot();
        this.cmdAutoHelp = new CmdAutoHelp();

        Econ.setup();
        setupPermissions();

        if (Conf.worldGuardChecking || Conf.worldGuardBuildPriority) {
            Worldguard.init(this);
        }

        EngineDynmap.getInstance().init();

        // start up task which runs the autoLeaveAfterDaysOfInactivity routine
        startAutoLeaveTask(false);

        if (version > 8) {
            useNonPacketParticles = true;
            log("Minecraft Version 1.9 or higher found, using non packet based particle API");
        }

        if (getConfig().getBoolean("enable-faction-flight")) {
            factionsFlight = true;
        }

        if (getServer().getPluginManager().getPlugin("Skript") != null) {
            log("Skript was found! Registering SavageFactions Addon...");
            skriptAddon = Skript.registerAddon(this);
            try {
                skriptAddon.loadClasses("com.massivecraft.factions.skript", "expressions");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            log("Skript addon registered!");
        }


        // Register Event Handlers
        eventsListener = new Listener[]{
                new FactionsPlayerListener(),
                new FactionsChatListener(),
                new FactionsEntityListener(),
                new FactionsExploitListener(),
                new FactionsBlockListener(),
                new UpgradeListener(),
        };

        for (Listener eventListener : eventsListener)
            getServer().getPluginManager().registerEvents(eventListener, this);

        // since some other plugins execute commands directly through this command interface, provide it
        this.getCommand(refCommand).setExecutor(cmdBase);

        if (!CommodoreProvider.isSupported()) {
            this.getCommand(refCommand).setTabCompleter(this);
        }

        if (getDescription().getFullName().contains("BETA") || getDescription().getFullName().contains("ALPHA")) {
            divider();
            System.out.println("You are using an unstable version of the plugin!");
            System.out.println("This comes with risks of small bugs in newer features!");
            System.out.println("For support head to: https://github.com/ProSavage/SavageFactions/issues");
            divider();
        }

        this.setupPlaceholderAPI();
        this.postEnable();
        this.loadSuccessful = true;
        // Set startup finished to true. to give plugins hooking in a greenlight
        SavageFactions.startupFinished = true;


    }

    public SkriptAddon getSkriptAddon() {
        return skriptAddon;
    }

    private void setupPlaceholderAPI() {
        Plugin clip = getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (clip != null && clip.isEnabled()) {
            this.clipPlaceholderAPIManager = new ClipPlaceholderAPIManager(this);
            if (this.clipPlaceholderAPIManager.register()) {
                PlaceholderApi = true;
                log(Level.INFO, "Successfully registered placeholders with PlaceholderAPI.");
            } else {
                PlaceholderApi = false;
            }
        } else {
            PlaceholderApi = false;
        }

        Plugin mvdw = getServer().getPluginManager().getPlugin("MVdWPlaceholderAPI");
        if (mvdw != null && mvdw.isEnabled()) {
            this.mvdwPlaceholderAPIManager = true;
            log(Level.INFO, "Found MVdWPlaceholderAPI. Adding hooks.");
        }
    }

    public List<String> replacePlaceholders(List<String> lore, Placeholder... placeholders) {
        for (Placeholder placeholder : placeholders) {
            for (int x = 0; x <= lore.size() - 1; x++)
                lore.set(x, lore.get(x).replace(placeholder.getTag(), placeholder.getReplace()));
        }
        return lore;
    }

    private void migrateFPlayerLeaders() {
        List<String> lines = new ArrayList<>();
        File fplayerFile = new File("plugins" + File.pathSeparator + "Factions" + File.pathSeparator + "players.json");

        try {
            BufferedReader br = new BufferedReader(new FileReader(fplayerFile));
            System.out.println("Migrating old players.json file.");

            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("\"role\": \"ADMIN\"")) {
                    line = line.replace("\"role\": \"ADMIN\"", "\"role\": " + "\"LEADER\"");
                }
                lines.add(line);
            }
            br.close();
            BufferedWriter bw = new BufferedWriter(new FileWriter(fplayerFile));
            for (String newLine : lines) {
                bw.write(newLine + "\n");
            }
            bw.flush();
            bw.close();
        } catch (IOException ex) {
            System.out.println("File was not found for players.json, assuming"
                    + " there is no need to migrate old players.json file.");
        }
    }

    public boolean isClipPlaceholderAPIHooked() {
        return this.clipPlaceholderAPIManager != null;
    }

    public boolean isMVdWPlaceholderAPIHooked() {
        return this.mvdwPlaceholderAPIManager;
    }

    private boolean setupPermissions() {
        try {
            RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
            if (rsp != null) {
                perms = rsp.getProvider();
            }
        } catch (NoClassDefFoundError ex) {
            return false;
        }
        return perms != null;
    }

    private boolean setupPlayervaults() {
        Plugin plugin = getServer().getPluginManager().getPlugin("PlayerVaults");
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public GsonBuilder getGsonBuilder() {
        Type mapFLocToStringSetType = new TypeToken<Map<FLocation, Set<String>>>() {
        }.getType();

        Type accessTypeAdatper = new TypeToken<Map<Permissable, Map<PermissableAction, Access>>>() {
        }.getType();

        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().enableComplexMapKeySerialization().excludeFieldsWithModifiers(Modifier.TRANSIENT, Modifier.VOLATILE)
                .registerTypeAdapter(accessTypeAdatper, new PermissionsMapTypeAdapter())
                .registerTypeAdapter(LazyLocation.class, new MyLocationTypeAdapter())
                .registerTypeAdapter(mapFLocToStringSetType, new MapFLocToStringSetTypeAdapter())
                .registerTypeAdapter(Inventory.class, new InventoryTypeAdapter())
                .registerTypeAdapter(Location.class, new LocationTypeAdapter())
                .registerTypeAdapter(FlyParticleData.class, new FlyParticleDataTypeAdapter())
                .registerTypeAdapterFactory(EnumTypeAdapter.ENUM_FACTORY);
    }

    private void divider() {
        System.out.println("  .-.-.   .-.-.   .-.-.   .-.-.   .-.-.   .-.-.   .-.-.   .-.-");
        System.out.println(" / / \\ \\ / / \\ \\ / / \\ \\ / / \\ \\ / / \\ \\ / / \\ \\ / / \\ \\ / / \\");
        System.out.println("`-'   `-`-'   `-`-'   `-`-'   `-`-'   `-`-'   `-`-'   `-`-'");
    }

    @Override
    public void onDisable() {
        // only save data if plugin actually completely loaded successfully
        if (this.loadSuccessful) {
            // Dont save, as this is kind of pointless, as the /f config command manually saves.
            // So any edits done are saved, this way manual edits to json can go through.

            // Conf.save();
        }

        if (AutoLeaveTask != null) {
            this.getServer().getScheduler().cancelTask(AutoLeaveTask);
            AutoLeaveTask = null;
        }

        super.onDisable();
    }

    public void startAutoLeaveTask(boolean restartIfRunning) {
        if (AutoLeaveTask != null) {
            if (!restartIfRunning) {
                return;
            }
            this.getServer().getScheduler().cancelTask(AutoLeaveTask);
        }

        if (Conf.autoLeaveRoutineRunsEveryXMinutes > 0.0) {
            long ticks = (long) (20 * 60 * Conf.autoLeaveRoutineRunsEveryXMinutes);
            AutoLeaveTask = getServer().getScheduler().scheduleSyncRepeatingTask(this, new AutoLeaveTask(), ticks, ticks);
        }
    }

    @Override
    public void postAutoSave() {
        //Board.getInstance().forceSave(); Not sure why this was there as it's called after the board is already saved.
        Conf.save();
    }

    public ItemStack createItem(Material material, int amount, short datavalue, String name, List<String> lore) {
        ItemStack item = new ItemStack(XMaterial.matchXMaterial(material.toString()).parseMaterial(), amount, datavalue);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLore(colorList(lore));
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createLazyItem(Material material, int amount, short datavalue, String name, String lore) {
        ItemStack item = new ItemStack(material, amount, datavalue);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(SavageFactions.plugin.getConfig().getString(name)));
        meta.setLore(colorList(SavageFactions.plugin.getConfig().getStringList(lore)));
        item.setItemMeta(meta);
        return item;
    }

    public Economy getEcon() {
        RegisteredServiceProvider<Economy> rsp = SavageFactions.plugin.getServer().getServicesManager().getRegistration(Economy.class);
        Economy econ = rsp.getProvider();
        return econ;
    }


    @Override
    public boolean logPlayerCommands() {
        return Conf.logPlayerCommands;
    }

    public void createTimedHologram(final Location location, String text, Long timeout) {
        ArmorStand as = (ArmorStand) location.add(0.5, 1, 0.5).getWorld().spawnEntity(location, EntityType.ARMOR_STAND); //Spawn the ArmorStand
        as.setVisible(false); //Makes the ArmorStand invisible
        as.setGravity(false); //Make sure it doesn't fall
        as.setCanPickupItems(false); //I'm not sure what happens if you leave this as it is, but you might as well disable it
        as.setCustomName(SavageFactions.plugin.color(text)); //Set this to the text you want
        as.setCustomNameVisible(true); //This makes the text appear no matter if your looking at the entity or not
        final ArmorStand armorStand = as;

        Bukkit.getScheduler().scheduleSyncDelayedTask(SavageFactions.plugin, () -> {
                    armorStand.remove();
                    getLogger().info("Removing Hologram.");
                }
                , timeout * 20);
    }

    @Override
    public boolean handleCommand(CommandSender sender, String commandString, boolean testOnly) {
        return sender instanceof Player && FactionsPlayerListener.preventCommand(commandString, (Player) sender) || super.handleCommand(sender, commandString, testOnly);
    }

    // This method must stay for < 1.12 versions
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Must be a LinkedList to prevent UnsupportedOperationException.
        List<String> argsList = new LinkedList<>(Arrays.asList(args));
        CommandContext context = new CommandContext(sender, argsList, alias);
        String cmd = Conf.baseCommandAliases.isEmpty() ? "/f" : "/" + Conf.baseCommandAliases.get(0);
        String cmdValid = (cmd + " " + TextUtil.implode(context.args, " ")).trim();
        List<FCommand> commandsList = cmdBase.subCommands;
        FCommand commandsEx = cmdBase;
        List<String> completions = new ArrayList<>();

        if (context.args.size() == 1) {
            for (; !commandsList.isEmpty() && !context.args.isEmpty(); context.args.remove(0)) {
                String cmdName = context.args.get(0).toLowerCase();
                boolean toggle = false;
                for (FCommand fCommand : commandsList) {
                    for (String s : fCommand.aliases) {
                        if (s.startsWith(cmdName)) {
                            commandsEx = fCommand;
                            commandsList = fCommand.subCommands;
                            completions.addAll(fCommand.aliases);
                            toggle = true;
                            break;
                        }
                    }
                    if (toggle) break;
                }

            }
            if (context.args.isEmpty()) {
                for (FCommand subCommand : commandsEx.subCommands) {
                    if (handleCommand(sender, cmdValid + " " + subCommand.aliases.get(0), true)
                            && subCommand.requirements.playerOnly
                            && sender.hasPermission(subCommand.requirements.permission.node)
                            && subCommand.visibility != CommandVisibility.INVISIBLE)
                        completions.addAll(subCommand.aliases);
                }
            }
            String lastArg = args[args.length - 1].toLowerCase();

            completions = completions.stream()
                    .filter(m -> m.toLowerCase().startsWith(lastArg))
                    .collect(Collectors.toList());

            return completions;

        } else {
            String lastArg = args[args.length - 1].toLowerCase();
            if (context.args.size() >= 2) {
                for (Role value : Role.values()) completions.add(value.nicename);
                for (Relation value : Relation.values()) completions.add(value.nicename);
                for (Player player : Bukkit.getServer().getOnlinePlayers()) completions.add(player.getName());

               List<Faction> facs = Factions.getInstance().getAllFactions().stream().filter(f -> f.getTag().startsWith(lastArg)).collect(Collectors.toList());
                for (Faction fac : facs) completions.add(fac.getTag());

            }
            completions = completions.stream()
                    .filter(m -> m.toLowerCase().startsWith(lastArg))
                    .collect(Collectors.toList());

            return completions;
        }
    }


    // -------------------------------------------- //
    // Functions for other plugins to hook into
    // -------------------------------------------- //

    // This value will be updated whenever new hooks are added
    public int hookSupportVersion() {
        return 3;
    }

    // If another plugin is handling insertion of chat tags, this should be used to notify Factions
    public void handleFactionTagExternally(boolean notByFactions) {
        Conf.chatTagHandledByAnotherPlugin = notByFactions;
    }

    // Simply put, should this chat event be left for Factions to handle? For now, that means players with Faction Chat
    // enabled or use of the Factions f command without a slash; combination of isPlayerFactionChatting() and isFactionsCommand()

    public boolean shouldLetFactionsHandleThisChat(AsyncPlayerChatEvent event) {
        return event != null && (isPlayerFactionChatting(event.getPlayer()) || isFactionsCommand(event.getMessage()));
    }


    // Does player have Faction Chat enabled? If so, chat plugins should preferably not do channels,
    // local chat, or anything else which targets individual recipients, so Faction Chat can be done
    public boolean isPlayerFactionChatting(Player player) {
        if (player == null) {
            return false;
        }
        FPlayer me = FPlayers.getInstance().getByPlayer(player);

        return me != null && me.getChatMode().isAtLeast(ChatMode.ALLIANCE);
    }

    // Is this chat message actually a Factions command, and thus should be left alone by other plugins?

    // TODO: GET THIS BACK AND WORKING

    public boolean isFactionsCommand(String check) {
        return !(check == null || check.isEmpty());
    }

    // Get a player's faction tag (faction name), mainly for usage by chat plugins for local/channel chat
    public String getPlayerFactionTag(Player player) {
        return getPlayerFactionTagRelation(player, null);
    }

    // Same as above, but with relation (enemy/neutral/ally) coloring potentially added to the tag
    public String getPlayerFactionTagRelation(Player speaker, Player listener) {
        String tag = "~";

        if (speaker == null) {
            return tag;
        }

        FPlayer me = FPlayers.getInstance().getByPlayer(speaker);
        if (me == null) {
            return tag;
        }

        // if listener isn't set, or config option is disabled, give back uncolored tag
        if (listener == null || !Conf.chatTagRelationColored) {
            tag = me.getChatTag().trim();
        } else {
            FPlayer you = FPlayers.getInstance().getByPlayer(listener);
            if (you == null) {
                tag = me.getChatTag().trim();
            } else  // everything checks out, give the colored tag
            {
                tag = me.getChatTag(you).trim();
            }
        }
        if (tag.isEmpty()) {
            tag = "~";
        }

        return tag;
    }

    // Get a player's title within their faction, mainly for usage by chat plugins for local/channel chat
    public String getPlayerTitle(Player player) {
        if (player == null) {
            return "";
        }

        FPlayer me = FPlayers.getInstance().getByPlayer(player);
        if (me == null) {
            return "";
        }

        return me.getTitle().trim();
    }

    public String color(String line) {
        line = ChatColor.translateAlternateColorCodes('&', line);
        return line;
    }

    //colors a string list
    public List<String> colorList(List<String> lore) {
        for (int i = 0; i <= lore.size() - 1; i++) {
            lore.set(i, color(lore.get(i)));
        }
        return lore;
    }

    // Get a list of all faction tags (names)
    public Set<String> getFactionTags() {
        return Factions.getInstance().getFactionTags();
    }

    // Get a list of all players in the specified faction
    public Set<String> getPlayersInFaction(String factionTag) {
        Set<String> players = new HashSet<>();
        Faction faction = Factions.getInstance().getByTag(factionTag);
        if (faction != null) {
            for (FPlayer fplayer : faction.getFPlayers()) {
                players.add(fplayer.getName());
            }
        }
        return players;
    }

    // Get a list of all online players in the specified faction
    public Set<String> getOnlinePlayersInFaction(String factionTag) {
        Set<String> players = new HashSet<>();
        Faction faction = Factions.getInstance().getByTag(factionTag);
        if (faction != null) {
            for (FPlayer fplayer : faction.getFPlayersWhereOnline(true)) {
                players.add(fplayer.getName());
            }
        }
        return players;
    }

    public boolean isHookedPlayervaults() {
        return hookedPlayervaults;
    }

    public String getPrimaryGroup(OfflinePlayer player) {
        AtomicReference<String> primaryGroup = new AtomicReference<>();

        if (perms == null || !perms.hasGroupSupport()) return " ";
        else {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> primaryGroup.set(perms.getPrimaryGroup(Bukkit.getWorlds().get(0).toString(), player)));
            return primaryGroup.get();
        }
    }

    public void debug(Level level, String s) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().log(level, s);
        }
    }

    public void debug(String s) {
        debug(Level.INFO, s);
    }
}
