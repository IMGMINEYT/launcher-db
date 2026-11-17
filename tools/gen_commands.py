#!/usr/bin/env python3
"""Generates app/src/main assets/commands.json: curated Bedrock command templates.
Every entry: n=name(what it does), c=command text, cb=ok in command block, note=syntax/cheat/version caveat, warn=danger flag.
Bedrock syntax only (no Java NBT). Identifiers are current minecraft:* string ids."""
import json, io, os, re

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "assets", "commands.json")

E = []  # entries

def add(cat, n, c, cb=False, note="", warn=False):
    E.append({"cat": cat, "n": n, "c": c, "cb": cb, "note": note, "warn": warn})

CHEAT = "World cheats must be on"

# ---- core chat/world commands (curated, both categories where sensible) ----
add("chat", "Creative mode (self)", "/gamemode creative @s", False, CHEAT)
add("chat", "Survival mode (self)", "/gamemode survival @s", False, CHEAT)
add("chat", "Adventure mode (self)", "/gamemode adventure @s", False, CHEAT)
add("chat", "Spectator mode (self)", "/gamemode spectator @s", False, CHEAT)
add("chat", "Creative for everyone", "/gamemode creative @a", True, CHEAT)
add("chat", "Set time to day", "/time set day", True, CHEAT)
add("chat", "Set time to noon", "/time set noon", True, CHEAT)
add("chat", "Set time to night", "/time set night", True, CHEAT)
add("chat", "Set time to midnight", "/time set midnight", True, CHEAT)
add("chat", "Set exact tick", "/time set 18000", True, CHEAT)
add("chat", "Add 1000 ticks", "/time add 1000", True, CHEAT)
add("chat", "Check the time", "/time query daytime", True, "")
add("chat", "Clear weather", "/weather clear", True, CHEAT)
add("chat", "Rain", "/weather rain", True, CHEAT)
add("chat", "Thunderstorm", "/weather thunder", True, CHEAT)
add("chat", "Clear weather for a long time", "/weather clear 1000000", True, CHEAT + "; duration in seconds")
add("chat", "Peaceful difficulty", "/difficulty peaceful", True, CHEAT)
add("chat", "Easy difficulty", "/difficulty easy", True, CHEAT)
add("chat", "Normal difficulty", "/difficulty normal", True, CHEAT)
add("chat", "Hard difficulty", "/difficulty hard", True, CHEAT + "; mobs hit harder", True)
add("chat", "Always day (lock)", "/alwaysday true", True, CHEAT)
add("chat", "Unlock day cycle", "/alwaysday false", True, CHEAT)
add("chat", "Stop rain/snow cycle", "/toggledownfall", True, CHEAT)
add("chat", "Set your spawn here", "/spawnpoint @s ~ ~ ~", False, "")
add("chat", "Clear your spawn point", "/clearspawnpoint @s", False, "")
add("chat", "Set world spawn here", "/setworldspawn ~ ~ ~", True, CHEAT)
add("chat", "Where am I (coords)", "/execute as @s at @s run tellraw @s {\"rawtext\":[{\"text\":\"Check your position in settings or use /tp ~ ~ ~\"}]}", False, "Shows a chat note; Bedrock prints coords with /tp ~ ~ ~")
add("chat", "Say something as server", "/say Hello everyone!", True, "")
add("chat", "Narrate an action", "/me is building a castle", True, "")
add("chat", "Private message a player", "/tell PlayerName hi, come see my base", True, "Replace PlayerName")
add("chat", "Whisper with /msg", "/msg PlayerName secret meeting at spawn", True, "Replace PlayerName")
add("chat", "List players online", "/list", True, "")
add("chat", "Title splash to all", "/title @a title WELCOME", True, "")
add("chat", "Subtitle under title", "/title @a subtitle event starts now", True, "")
add("chat", "Actionbar text", "/title @a actionbar 10 minutes left", True, "")
add("chat", "Clear titles", "/title @a clear", True, "")
add("chat", "Title timings (fade in 10, stay 70, out 20)", "/title @a times 10 70 20", True, "Ticks: 20 = 1 second")
add("chat", "Kill yourself (respawn)", "/kill @s", False, "", True)
add("chat", "Remove arrows from your inventory", "/clear @s minecraft:arrow", False, "", True)
add("chat", "Clear your whole inventory", "/clear @s", False, "Deletes ALL items you carry", True)
add("chat", "Give yourself 100 XP levels", "/xp 100L @s", False, CHEAT + "; L = levels")
add("chat", "Give yourself 500 XP points", "/xp 500 @s", False, CHEAT)
add("chat", "Take 5 levels", "/xp -5L @s", False, CHEAT)

# ---- gamerule toggles (each is its own useful daily command) ----
RULES = [
    ("keepInventory", "Keep items after death"), ("mobGriefing", "Mobs can't break blocks (creepers safe)"),
    ("doDaylightCycle", "Stop/start day cycle"), ("doWeatherCycle", "Stop/start weather changes"),
    ("doMobSpawning", "Mob spawning on/off"), ("doMobLoot", "Mobs drop loot"),
    ("doTileDrops", "Blocks drop items when broken"), ("doFireTick", "Fire spread on/off"),
    ("mobGriefing", "Creeper/ghast block damage"), ("tntExplodes", "TNT explosion on/off"),
    ("sendCommandFeedback", "Command feedback in chat"), ("showDeathMessages", "Death messages on/off"),
    ("naturalRegeneration", "Health regen on/off"), ("doImmediateRespawn", "Respawn instantly"),
    ("drowningDamage", "Drowning damage on/off"), ("fallDamage", "Fall damage on/off"),
    ("fireDamage", "Fire damage on/off"), ("freezeDamage", "Powder snow freeze on/off"),
    ("doInsomnia", "Phantoms spawn on/off"), ("commandBlockOutput", "Command block spam on/off"),
    ("randomTickSpeed", "Crop/plant growth speed"), ("spawnRadius", "Spawn scatter radius"),
    ("pvp", "Player vs player damage"), ("falldamage", "Fall damage toggle"),
    ("doEntityDrops", "Non-mob entity drops"), ("doLimitedCrafting", "Recipes need unlocking"),
    ("mobEvents", "Raid/patrol events"), ("projectilesCanBreakBlocks", "Projectiles break blocks"),
    ("respawnBlocksExplode", "Beds/anchors explode"), ("playersSleepingPercentage", "% players needed to skip night"),
]
for r, d in RULES:
    if r == "randomTickSpeed":
        add("chat", d + " (fast)", "/gamerule randomTickSpeed 12", True, CHEAT + "; default 1")
        add("chat", d + " (default)", "/gamerule randomTickSpeed 1", True, CHEAT)
    elif r == "playersSleepingPercentage":
        add("chat", "One player skips night", "/gamerule playersSleepingPercentage 0", True, CHEAT)
        add("chat", d + " (default 100)", "/gamerule playersSleepingPercentage 100", True, CHEAT)
    elif r == "spawnRadius":
        add("chat", d + " small", "/gamerule spawnRadius 10", True, CHEAT)
    else:
        add("chat", d + " ON", "/gamerule " + r + " true", True, CHEAT)
        add("chat", d + " OFF", "/gamerule " + r + " false", True, CHEAT)

# ---- /give for common items ----
ITEMS = [
    ("diamond", 64), ("diamond_sword", 1), ("diamond_pickaxe", 1), ("diamond_axe", 1), ("diamond_shovel", 1),
    ("diamond_hoe", 1), ("diamond_helmet", 1), ("diamond_chestplate", 1), ("diamond_leggings", 1), ("diamond_boots", 1),
    ("netherite_sword", 1), ("netherite_pickaxe", 1), ("netherite_axe", 1), ("netherite_helmet", 1),
    ("netherite_chestplate", 1), ("netherite_leggings", 1), ("netherite_boots", 1), ("netherite_ingot", 16),
    ("iron_sword", 1), ("iron_pickaxe", 1), ("iron_axe", 1), ("iron_helmet", 1), ("iron_chestplate", 1),
    ("iron_leggings", 1), ("iron_boots", 1), ("iron_ingot", 64), ("gold_ingot", 64),
    ("golden_apple", 16), ("enchanted_golden_apple", 8), ("totem_of_undying", 1), ("elytra", 1),
    ("trident", 1), ("bow", 1), ("crossbow", 1), ("arrow", 64),
    ("shield", 1), ("mace", 1), ("fishing_rod", 1), ("flint_and_steel", 1), ("shears", 1),
    ("torch", 64), ("lantern", 32), ("glowstone", 64), ("sea_lantern", 32), ("shroomlight", 32),
    ("oak_log", 64), ("spruce_log", 64), ("birch_log", 64), ("jungle_log", 64), ("acacia_log", 64),
    ("dark_oak_log", 64), ("mangrove_log", 64), ("cherry_log", 64), ("bamboo_block", 64),
    ("stone", 64), ("cobblestone", 64), ("stone_bricks", 64), ("deepslate", 64), ("obsidian", 32),
    ("glass", 64), ("white_wool", 64), ("black_wool", 64), ("red_wool", 64), ("blue_wool", 64),
    ("brick_block", 64), ("quartz_block", 64), ("prismarine", 64), ("netherrack", 64), ("end_stone", 64),
    ("purpur_block", 64), ("amethyst_block", 32), ("copper_block", 32), ("tnt", 16),
    ("bed", 1), ("crafting_table", 1), ("furnace", 1), ("chest", 4), ("ender_chest", 1),
    ("anvil", 1), ("enchanting_table", 1), ("brewing_stand", 1), ("beacon", 1),
    ("bread", 32), ("cooked_beef", 32), ("cooked_porkchop", 32), ("cooked_chicken", 32),
    ("baked_potato", 32), ("golden_carrot", 32), ("cake", 1), ("cookie", 32),
    ("potion", 4), ("splash_potion", 4), ("experience_bottle", 32), ("ender_pearl", 16),
    ("ender_eye", 16), ("fire_charge", 16), ("firework_rocket", 64),
    ("oak_boat", 1), ("minecart", 1), ("rail", 64), ("golden_rail", 32), ("saddle", 1),
    ("lead", 4), ("name_tag", 4), ("bone", 32), ("string", 32), ("gunpowder", 32),
    ("book", 16), ("writable_book", 1), ("empty_map", 1), ("compass", 1), ("banner", 1),
    ("clock", 1), ("spyglass", 1), ("brush", 1), ("bundle", 1), ("undyed_shulker_box", 1),
    ("diamond_block", 16), ("emerald", 64), ("emerald_block", 16), ("lapis_lazuli", 64),
    ("redstone", 64), ("slime_ball", 32), ("honeycomb", 16), ("amethyst_shard", 32),
]
for it, n in ITEMS:
    if n == 0: continue
    nm = it.replace("_", " ")
    add("chat", "Give " + (str(n) + " " if n > 1 else "") + nm, "/give @s minecraft:" + it + (" " + str(n) if n > 1 else ""), False, CHEAT)

# ---- /summon mobs ----
MOBS = [
    "zombie", "skeleton", "creeper", "spider", "enderman", "witch", "slime", "zombie_villager",
    "husk", "stray", "drowned", "phantom", "pillager", "vindicator", "evocation_illager", "ravager",
    "vex", "silverfish", "endermite", "guardian", "elder_guardian", "blaze", "magma_cube",
    "ghast", "wither_skeleton", "hoglin", "piglin", "piglin_brute", "zoglin", "warden",
    "breeze", "bogged", "creeper", "villager", "wandering_trader", "iron_golem", "snow_golem",
    "wolf", "cat", "ocelot", "horse", "donkey", "mule", "cow", "mooshroom", "pig", "sheep",
    "chicken", "rabbit", "bee", "fox", "goat", "frog", "axolotl", "turtle", "dolphin",
    "squid", "glow_squid", "cod", "salmon", "pufferfish", "tropicalfish", "camel", "llama",
    "parrot", "armadillo", "bat", "polar_bear", "panda", "allay", "wither", "ender_dragon",
]
seen_m = set()
for m in MOBS:
    if m in seen_m: continue
    seen_m.add(m)
    nm = m.replace("_", " ")
    note = CHEAT
    w = False
    if m in ("wither", "ender_dragon", "warden"):
        note = CHEAT + "; boss mob - destructive and hard to kill"
        w = True
    add("chat", "Summon " + nm + " here", "/summon minecraft:" + m + " ~ ~ ~", True, note, w)

# ---- /effect ----
EFFECTS = [
    ("speed", "Speed"), ("slowness", "Slowness"), ("haste", "Haste (mine faster)"),
    ("strength", "Strength"), ("instant_health", "Instant heal"), ("jump_boost", "Jump boost"),
    ("regeneration", "Regeneration"), ("resistance", "Resistance"), ("fire_resistance", "Fire resistance"),
    ("water_breathing", "Water breathing"), ("invisibility", "Invisibility"), ("night_vision", "Night vision"),
    ("weakness", "Weakness"), ("poison", "Poison"), ("wither", "Wither"), ("absorption", "Absorption hearts"),
    ("saturation", "Saturation (full hunger)"), ("levitation", "Levitation"), ("slow_falling", "Slow falling"),
    ("conduit_power", "Conduit power"), ("village_hero", "Hero of the village"),
    ("darkness", "Darkness"), ("blindness", "Blindness"), ("nausea", "Nausea"),
    ("health_boost", "Health boost"), ("mining_fatigue", "Mining fatigue"), ("hunger", "Hunger"),
    ("bad_omen", "Bad omen (starts raids)"), ("trial_omen", "Trial omen"), ("raid_omen", "Raid omen"),
    ("wind_charged", "Wind charged"), ("weaving", "Weaving"), ("oozing", "Oozing"), ("infested", "Infested"),
]
for e, d in EFFECTS:
    w = e in ("poison", "wither", "instant_damage", "bad_omen", "raid_omen")
    add("chat", d + " for 60s", "/effect @s " + e + " 60 0", False, CHEAT + "; last number = level-1", w)
    add("chat", d + " strong, 30s", "/effect @s " + e + " 30 2", False, CHEAT + "; level 3", w)
add("chat", "Remove all effects (self)", "/effect @s clear", False, "")
add("chat", "Infinite night vision trick", "/effect @s night_vision 999999 0 true", False, CHEAT)

# ---- /enchant ----
ENCH = ["sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting",
        "efficiency", "unbreaking", "fortune", "silk_touch", "power", "punch", "flame",
        "infinity", "protection", "fire_protection", "blast_protection", "projectile_protection",
        "thorns", "feather_falling", "depth_strider", "frost_walker", "respiration", "aqua_affinity",
        "loyalty", "impaling", "riptide", "channeling", "mending", "multishot", "quick_charge",
        "piercing", "density", "breach", "wind_burst", "soul_speed", "swift_sneak", "lure", "luck_of_the_sea", "binding", "vanishing", "lunge"]
for en in ENCH:
    add("chat", "Enchant held item: " + en.replace("_", " "), "/enchant @s " + en + " 1", False,
        CHEAT + "; hold the item first; level must fit the enchant")

# ---- blocks: /setblock + /fill ----
BLOCKS = ["stone", "cobblestone", "stone_bricks", "oak_planks", "glass", "obsidian", "bedrock",
          "diamond_block", "gold_block", "iron_block", "emerald_block", "glowstone", "sea_lantern",
          "white_wool", "black_wool", "brick_block", "quartz_block", "prismarine", "amethyst_block",
          "copper_block", "tnt", "water", "lava", "air", "sand", "gravel", "grass_block", "dirt",
          "snow", "ice", "packed_ice", "blue_ice", "honey_block", "slime", "magma"]
for b in BLOCKS:
    w = b in ("tnt", "lava")
    add("chat", "Place " + b.replace("_", " ") + " at your feet", "/setblock ~ ~-1 ~ minecraft:" + b, True, CHEAT, w)
for b in ["stone_bricks", "glass", "obsidian", "white_wool", "quartz_block"]:
    add("chat", "Fill 10x1x10 floor of " + b.replace("_", " "), "/fill ~-5 ~-1 ~-5 ~5 ~-1 ~5 minecraft:" + b, True, CHEAT)
add("chat", "Clear a 10x10x10 cube (air)", "/fill ~-5 ~ ~-5 ~5 ~9 ~5 minecraft:air", True, CHEAT + "; deletes blocks", True)
add("chat", "Clone a 8x8x8 area 20 blocks north", "/clone ~-4 ~ ~-4 ~4 ~7 ~4 ~-4 ~ ~-20", True, CHEAT)

# ---- tp ----
add("chat", "Teleport to a player", "/tp @s PlayerName", False, "Replace PlayerName")
add("chat", "Bring a player to you", "/tp PlayerName @s", True, "Replace PlayerName")
add("chat", "Teleport everyone to you", "/tp @a @s", True, "")
add("chat", "Teleport to coordinates", "/tp @s 100 64 -250", False, "Replace with target coords")
add("chat", "Teleport 50 blocks up", "/tp @s ~ ~50 ~", False, "")
add("chat", "Teleport to the nearest village (after /locate)", "/tp @s <x> ~ <z>", False, "Use coords from /locate structure village")

# ---- locate ----
for s in ["village", "stronghold", "mineshaft", "temple", "monument", "mansion",
          "pillager_outpost", "ancient_city", "trial_chambers", "bastion_remnant",
          "fortress", "end_city", "shipwreck", "buried_treasure", "ruined_portal",
          "trail_ruins", "ruins"]:
    add("chat", "Find nearest " + s.replace("_", " "), "/locate structure minecraft:" + s, False, CHEAT)

# ---- scoreboard kit ----
add("chat", "Make a kills board", "/scoreboard objectives add kills dummy KILLS", True, CHEAT)
add("chat", "Show kills on sidebar", "/scoreboard objectives setdisplay sidebar kills", True, CHEAT)
add("chat", "Give yourself 1 point", "/scoreboard players add @s kills 1", True, CHEAT)
add("chat", "Set a player's score", "/scoreboard players set PlayerName kills 10", True, CHEAT)
add("chat", "Remove the board", "/scoreboard objectives remove kills", True, CHEAT, True)
add("chat", "List all boards", "/scoreboard objectives list", True, "")

# ---- particle / sound ----
PARTS = ["minecraft:heart_particle", "minecraft:crit_emitter", "minecraft:explosion_particle",
         "minecraft:flame_particle", "minecraft:smoke_particle", "minecraft:water_splash_particle",
         "minecraft:totem_particle", "minecraft:villager_happy", "minecraft:villager_angry",
         "minecraft:note_particle", "minecraft:enchanting_table_particle", "minecraft:endrod",
         "minecraft:portal_particle", "minecraft:dragon_breath_trail", "minecraft:soul_particle"]
for p in PARTS:
    add("chat", "Particles: " + p.split(":")[1].replace("_", " "), "/particle " + p + " ~ ~1 ~", True, "")
SOUNDS = ["random.levelup", "random.orb", "random.explode", "mob.enderdragon.growl",
          "mob.wither.spawn", "raid.horn", "mob.villager.yes", "note.pling", "firework.launch"]
for s in SOUNDS:
    add("chat", "Play sound " + s, "/playsound " + s + " @s ~ ~ ~ 1 1", True, "")

# ---- spawn eggs (real Bedrock items: <mob>_spawn_egg) ----
EGGNAME = {"evocation_illager": "evoker", "tropicalfish": "tropical_fish"}
for m in sorted(seen_m):
    if m in ("wither", "ender_dragon"):
        continue  # no spawn egg for bosses
    egg = EGGNAME.get(m, m)
    add("chat", "Spawn egg: " + m.replace("_", " "), "/give @s minecraft:" + egg + "_spawn_egg 4", False, CHEAT)

# ---- color families (16 colors each) ----
COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
          "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"]
for fam, per in [("wool", 16), ("concrete", 16), ("terracotta", 16), ("stained_glass", 16),
                 ("carpet", 8), ("candle", 8), ("dye", 16), ("shulker_box", 1)]:
    for col in COLORS:
        add("chat", "Give " + col.replace("_", " ") + " " + fam.replace("_", " "),
            "/give @s minecraft:" + col + "_" + fam + " " + str(per), False, CHEAT)

# ---- tool/weapon sets per material ----
for mat in ["wooden", "stone", "golden", "copper"]:
    for tool in ["sword", "pickaxe", "axe", "shovel", "hoe"]:
        add("chat", "Give " + mat + " " + tool, "/give @s minecraft:" + mat + "_" + tool + " 1", False, CHEAT)
for mat in ["chainmail", "golden", "leather", "copper"]:
    for piece in ["helmet", "chestplate", "leggings", "boots"]:
        add("chat", "Give " + mat + " " + piece, "/give @s minecraft:" + mat + "_" + piece + " 1", False, CHEAT)

# ---- music discs ----
for d in ["13", "cat", "blocks", "chirp", "far", "mall", "mellohi", "stal", "strad",
          "ward", "11", "wait", "otherside", "pigstep", "5", "relic", "creator", "creator_music_box", "precipice", "tears", "lava_chicken"]:
    add("chat", "Music disc " + d, "/give @s minecraft:music_disc_" + d + " 1", False, CHEAT)

# ---- smithing templates + pottery sherds ----
for t in ["netherite_upgrade", "sentry", "dune", "coast", "wild", "ward", "eye", "vex",
          "tide", "snout", "rib", "spire", "wayfinder", "shaper", "silence", "raiser", "host", "flow", "bolt"]:
    add("chat", "Smithing template: " + t.replace("_", " "), "/give @s minecraft:" + t + "_armor_trim_smithing_template" + " 2" if t != "netherite_upgrade" else "/give @s minecraft:netherite_upgrade_smithing_template 2", False, CHEAT)
for s in ["angler", "archer", "arms_up", "blade", "brewer", "burn", "danger", "explorer",
          "flow", "friend", "guster", "heart", "heartbreak", "howl", "miner", "mourner",
          "plenty", "prize", "scrape", "sheaf", "shelter", "skull", "snort"]:
    add("chat", "Pottery sherd: " + s.replace("_", " "), "/give @s minecraft:" + s + "_pottery_sherd 4", False, CHEAT)

# ---- flowers + saplings ----
for f in ["dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
          "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower",
          "lily_of_the_valley", "torchflower", "wither_rose", "pink_petals", "sunflower", "lilac", "rose_bush", "peony"]:
    add("chat", "Give " + f.replace("_", " "), "/give @s minecraft:" + f + " 8", False, CHEAT)
for s in ["oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling", "acacia_sapling",
          "dark_oak_sapling", "mangrove_propagule", "cherry_sapling", "azalea", "flowering_azalea"]:
    add("chat", "Give " + s.replace("_", " "), "/give @s minecraft:" + s + " 8", False, CHEAT)

# ---- more curated utility templates ----
add("chat", "Damage nearest zombie 10 hearts", "/damage @e[type=minecraft:zombie,c=1] 20", True, CHEAT + "; amount in half-hearts", True)
add("chat", "Find a block nearby", "/testforblock ~ ~-1 ~ minecraft:diamond_ore", True, "Checks the block under you")
add("chat", "Add ticking area here", "/tickingarea add ~-50 ~-50 ~-50 ~50 ~50 ~50 base", True, CHEAT + "; keeps area loaded")
add("chat", "Remove the ticking area", "/tickingarea remove base", True, CHEAT)
add("chat", "List ticking areas", "/tickingarea list", True, "")
add("chat", "Camera shake (fun)", "/camerashake add @s 0.4 2 rotational", True, "")
add("chat", "Freeze a player in place", "/inputpermission set PlayerName movement disabled", True, "Replace PlayerName", True)
add("chat", "Unfreeze a player", "/inputpermission set PlayerName movement enabled", True, "")
add("chat", "Hide the HUD for screenshots", "/hud @s hide all", True, "")
add("chat", "Show the HUD again", "/hud @s reset all", True, "")
add("chat", "Stop all sounds", "/stopsound @s", True, "")
add("chat", "Fog: nether look", "/fog @s push minecraft:fog_nether nether", True, "")
add("chat", "Fog: remove it", "/fog @s pop nether", True, "")
add("chat", "Ride the nearest horse", "/ride @s start_riding @e[type=minecraft:horse,c=1]", False, CHEAT)
add("chat", "Entity event: make creeper charged", "/event entity @e[type=minecraft:creeper,c=1] minecraft:become_charged", True, CHEAT)
add("chat", "Raw JSON chat to all", "/titleraw @a actionbar {\"rawtext\":[{\"text\":\"\\u00a7aGreen text!\"}]}", True, "JSON text; \\\\u00a7 = section sign color codes")
add("chat", "Reload functions/loot tables", "/reload", True, "After editing behavior packs")
add("chat", "Run a function", "/function mypack:myfunction", True, "Replace with your function id")
add("chat", "Script event to add-ons", "/scriptevent mypack:start wave3", True, "For script-based add-ons")
add("chat", "Test for any player in 5 blocks", "/testfor @a[r=5]", True, "True if someone is close")
add("chat", "Execute as every player", "/execute as @a at @s run particle minecraft:heart_particle ~ ~1 ~", True, "New Bedrock execute syntax")
add("chat", "Execute if block matches", "/execute if block ~ ~-1 ~ minecraft:grass_block run say standing on grass", True, "New Bedrock execute syntax")
add("block", "Kit: repeating hearts over players", "/execute as @a at @s run particle minecraft:heart_particle ~ ~2 ~", True, "Repeating command block")
add("block", "Kit: kill arrow cleanup", "/kill @e[type=minecraft:arrow]", True, "Clears stuck arrows", True)
add("block", "Kit: countdown 3 (title)", "/title @a title 3", True, "Chain: 3 -> 2 -> 1 -> GO")
add("block", "Kit: countdown 2 (title)", "/title @a title 2", True, "")
add("block", "Kit: countdown 1 (title)", "/title @a title 1", True, "")
add("block", "Kit: GO (title + sound)", "/title @a title GO!", True, "Chain with /playsound random.levelup @a")
add("block", "Kit: reset deaths board", "/scoreboard players reset @a deaths", True, CHEAT, True)
add("block", "Kit: night arena start", "/time set night", True, "")
add("block", "Kit: parkour checkpoint (chain)", "/spawnpoint @p[r=3] ~ ~ ~", True, "Repeating block on pressure plate line")
add("block", "Kit: random player picker", "/tag @r add chosen", True, "Then act on @a[tag=chosen]")
add("block", "Kit: clear the picker tag", "/tag @a remove chosen", True, "")

# ---- boats, wood families, redstone, ores, food extras ----
for w in ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "pale_oak"]:
    item = w + ("_raft" if w == "bamboo" else "_boat")
    add("chat", "Give " + w.replace("_", " ") + " boat", "/give @s minecraft:" + item + " 1", False, CHEAT)
for w in ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped"]:
    add("chat", "Give " + w.replace("_", " ") + " planks", "/give @s minecraft:" + w + "_planks 64", False, CHEAT)
for r in ["redstone_torch", "repeater", "comparator", "piston", "sticky_piston", "observer",
          "dispenser", "dropper", "hopper", "lever", "redstone_lamp", "noteblock", "target",
          "daylight_detector", "tripwire_hook", "trapped_chest", "lectern", "sculk_sensor",
          "calibrated_sculk_sensor", "crafter", "heavy_weighted_pressure_plate", "light_weighted_pressure_plate",
          "wooden_pressure_plate", "stone_pressure_plate", "polished_blackstone_pressure_plate", "wooden_button", "stone_button"]:
    add("chat", "Give " + r.replace("_", " "), "/give @s minecraft:" + r + " 4", False, CHEAT)
for o in ["coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore", "lapis_ore",
          "diamond_ore", "emerald_ore", "quartz_ore", "nether_gold_ore", "ancient_debris",
          "deepslate_diamond_ore", "deepslate_iron_ore", "deepslate_gold_ore"]:
    add("chat", "Give " + o.replace("_", " "), "/give @s minecraft:" + o + " 16", False, CHEAT)
for f in ["apple", "melon_slice", "carrot", "potato", "beetroot", "sweet_berries", "glow_berries",
          "chorus_fruit", "dried_kelp", "mushroom_stew", "rabbit_stew", "beetroot_soup",
          "suspicious_stew", "honey_bottle", "cooked_mutton", "cooked_salmon", "cooked_cod", "pumpkin_pie"]:
    add("chat", "Give " + f.replace("_", " "), "/give @s minecraft:" + f + " 16", False, CHEAT)
for x in ["barrier", "command_block", "chain_command_block", "repeating_command_block",
          "structure_block", "structure_void", "light_block_15", "jigsaw", "deny", "allow"]:
    add("chat", "Give " + x.replace("_", " "), "/give @s minecraft:" + x + " 1", True, CHEAT + "; operator item")
for h in ["leather_horse_armor", "iron_horse_armor", "golden_horse_armor", "diamond_horse_armor", "wolf_armor"]:
    add("chat", "Give " + h.replace("_", " "), "/give @s minecraft:" + h + " 1", False, CHEAT)
for b in ["cod_bucket", "salmon_bucket", "pufferfish_bucket", "tropical_fish_bucket", "axolotl_bucket", "tadpole_bucket"]:
    add("chat", "Give " + b.replace("_", " "), "/give @s minecraft:" + b + " 1", False, CHEAT)

# ---- command block kits (selector-based, made for blocks) ----
add("block", "Kit: give everyone at spawn a starter set", "/give @a[r=10] minecraft:stone_sword 1", True, "Run in a command block at spawn")
add("block", "Kit: heal everyone nearby", "/effect @a[r=10] instant_health 1 2", True, "")
add("block", "Kit: feed everyone nearby", "/effect @a[r=10] saturation 10 0", True, "")
add("block", "Kit: teleport pad (10 blocks up)", "/tp @p[r=2] ~ ~10 ~", True, "Step-on pad")
add("block", "Kit: adventure zone entry", "/gamemode adventure @p[r=5]", True, "")
add("block", "Kit: kill items on ground (lag clear)", "/kill @e[type=minecraft:item]", True, "Deletes dropped items", True)
add("block", "Kit: remove named mob type nearby", "/kill @e[type=minecraft:zombie,r=30]", True, "", True)
add("block", "Kit: count players nearby into score", "/scoreboard players set count players 0", True, "Then /execute as @a run ...")
add("block", "Kit: welcome new player title", "/title @a[tag=!welcomed] title WELCOME", True, "Pair with /tag @a add welcomed in a chain block")
add("block", "Kit: tag after welcome", "/tag @a[tag=!welcomed] add welcomed", True, "Chain after the title block")
add("block", "Kit: day button", "/time set day", True, "Button on impulse block")
add("block", "Kit: rain off button", "/weather clear", True, "")
add("block", "Kit: arena heal pad", "/effect @a[r=3] regeneration 5 2", True, "Repeating block, always active")
add("block", "Kit: checkpoint setter", "/spawnpoint @a[r=4] ~ ~ ~", True, "")
add("block", "Kit: spawn zombies wave", "/summon minecraft:zombie ~ ~1 ~", True, CHEAT)
add("block", "Kit: XP fountain", "/xp 10L @a[r=5]", True, CHEAT)
add("block", "Kit: barrier floor fill", "/fill ~-5 ~-1 ~-5 ~5 ~-1 ~5 minecraft:glass", True, "")
add("block", "Kit: everyone to arena", "/tp @a 100 65 100", True, "Replace coords")
add("block", "Kit: clear inventory gate", "/clear @a[r=3]", True, "Wipes items - for kit-based arenas", True)


VANILLA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vanilla")

def validate():
    """Drop entries whose identifiers are not in Mojang bedrock-samples command enums."""
    d = json.load(io.open(os.path.join(VANILLA, "mojang-commands.json"), encoding="utf-8"))
    en = {e["name"]: {v["value"] for v in e["values"]} for e in d["command_enums"]}
    ITEM, EFF, ENC, BLK, ENT, SF = (en["Item"], en["Effect"], en["Enchant"], en["Block"], en["EntityType"], en["StructureFeature"])
    kept, dropped = [], []
    for e in E:
        c, ok = e["c"], True
        for m in re.finditer(r"/give @\w (minecraft:[a-z0-9_]+)", c):
            ok &= m.group(1) in ITEM
        for m in re.finditer(r"/summon (minecraft:[a-z0-9_:]+)", c):
            ok &= m.group(1) in ENT
        for m in re.finditer(r"/effect @\w(?:\[[^]]*\])? ([a-z0-9_:]+)", c):
            ok &= (m.group(1) in EFF) or (m.group(1) == "clear")
        for m in re.finditer(r"/enchant @\w ([a-z0-9_:]+)", c):
            ok &= m.group(1) in ENC
        for m in re.finditer(r"(?:setblock|fill) (?:[~\d-]+ ){1,6}(minecraft:[a-z0-9_]+)", c):
            ok &= m.group(1) in BLK
        for m in re.finditer(r"/locate structure ([a-z0-9_:]+)", c):
            ok &= m.group(1) in SF
        (kept if ok else dropped).append(e)
    for e in dropped:
        print("DROPPED", e["n"], "|", e["c"])
    print("validation: kept", len(kept), "dropped", len(dropped))
    E[:] = kept

def iconof(e):
    c = e["c"]
    words=c.replace("minecraft:","").split()
    token=words[2] if c.startswith("/give ") and len(words)>2 else ""
    if token:
        material=token.split("_")[0]
        slot=next((x for x in ("sword","pickaxe","axe","shovel","hoe","helmet","chestplate","leggings","boots") if token.endswith("_"+x)),None)
        if slot and material in ("wooden","stone","iron","golden","diamond","netherite","leather","chainmail","gold"):
            motif={"pickaxe":"pick","shovel":"tool","hoe":"tool","chestplate":"armor"}.get(slot,slot)
            return "ic_"+motif+"_"+material
        for dye in ("light_blue","light_gray","white","orange","magenta","yellow","lime","pink","gray","cyan","purple","blue","brown","green","red","black"):
            if token.startswith(dye+"_"):
                for motif in ("wool","glass","bed"):
                    if motif in token:return "ic_"+motif+"_"+dye
                if "carpet" in token:return "ic_wool_"+dye
        tests=[("pickaxe","ic_pick"),("sword","ic_sword"),("_axe","ic_axe"),("shovel","ic_tool"),("hoe","ic_tool"),
          ("helmet","ic_armor"),("chestplate","ic_armor"),("leggings","ic_armor"),("boots","ic_armor"),("armor","ic_armor"),
          ("trident","ic_trident"),("bow","ic_bow"),("arrow","ic_bow"),("shield","ic_shield"),("elytra","ic_elytra"),
          ("_spawn_egg","ic_egg_red" if any(x in token for x in ["zombie","creeper","skeleton","wither","warden","spider","blaze","phantom","pillager","ravager"]) else "ic_egg_green"),
          ("bucket","ic_bucket"),("wool","ic_wool"),("carpet","ic_wool"),("glass","ic_glass"),("bed","ic_bed"),("music_disc","ic_disc"),
          ("rail","ic_rail"),("torch","ic_light"),("lantern","ic_light"),("candle","ic_light"),("glow","ic_light"),
          ("ore","ic_ore"),("log","ic_wood"),("planks","ic_wood"),("wood","ic_wood"),("sapling","ic_tree"),("leaves","ic_tree"),
          ("flower","ic_flower"),("tulip","ic_flower"),("orchid","ic_flower"),("poppy","ic_flower"),("dandelion","ic_flower"),
          ("redstone","ic_redstone"),("repeater","ic_redstone"),("comparator","ic_redstone"),("piston","ic_redstone"),("observer","ic_redstone"),("button","ic_redstone"),
          ("boat","ic_boat"),("raft","ic_boat"),("ingot","ic_ingot"),("nugget","ic_ingot"),("stair","ic_stairs"),("slab","ic_stairs"),("door","ic_door"),("fence","ic_door"),
          ("bread","ic_food"),("apple","ic_food"),("carrot","ic_food"),("potato","ic_food"),("cooked","ic_food"),("stew","ic_food"),("berries","ic_food"),("cookie","ic_food"),("cake","ic_food"),
          ("potion","ic_effect"),("book","ic_enchant"),("pearl","ic_tp"),("eye","ic_tp"),("compass","ic_locate"),("map","ic_locate"),("diamond","ic_give"),("emerald","ic_give")]
        for part,ic in tests:
            if part in token:return ic
        return "ic_block"  # remaining give templates are crafting/building materials
    if c.startswith("/gamemode creative"):return "ic_block"
    if c.startswith("/gamemode survival"):return "ic_sword_iron"
    if c.startswith("/gamemode spectator"):return "ic_elytra"
    if c.startswith("/time set night"):return "ic_night"
    if c.startswith("/time set day"):return "ic_day"
    if c.startswith("/weather rain"):return "ic_rain"
    if c.startswith("/weather clear"):return "ic_day"
    if c.startswith("/effect") and any(x in c for x in ("instant_health","regeneration","health_boost")):return "ic_health"
    if c.startswith("/effect") and "speed" in c:return "ic_speed"
    if c.startswith("/summon"): return "ic_summon_red" if e.get("warn") else "ic_summon"
    if "effect " in c:return "ic_effect"
    if "enchant " in c:return "ic_enchant"
    if c.startswith(("/setblock","/fill","/clone")):return "ic_block"
    if c.startswith("/locate"):return "ic_locate"
    if c.startswith(("/tp","/teleport")):return "ic_tp"
    if c.startswith("/gamerule"):return "ic_rule"
    if c.startswith("/scoreboard"):return "ic_score"
    if c.startswith(("/particle","/playsound","/stopsound")):return "ic_fx"
    if c.startswith(("/title","/tellraw","/say","/tell","/me","/msg")):return "ic_text"
    if c.startswith(("/weather","/time","/toggledownfall")):return "ic_weather"
    return "ic_cmd" if e["cat"]=="block" else "ic_misc"

def daily(e):
    c=e["c"]
    if e.get("warn") or e["cat"]!="chat":return False
    return c.startswith(("/time set day","/time set night","/weather clear","/weather rain","/gamemode survival","/gamemode creative","/difficulty normal","/difficulty peaceful","/spawnpoint","/tp @s","/locate structure minecraft:village","/effect @s night_vision","/effect @s clear","/effect @s water_breathing","/effect @s fire_resistance","/effect @s speed 60","/give @s minecraft:torch ","/give @s minecraft:bread ","/give @s minecraft:diamond_pickaxe ","/give @s minecraft:iron_sword ","/give @s minecraft:oak_planks ","/give @s minecraft:crafting_table ","/give @s minecraft:bed ","/give @s minecraft:compass ","/gamerule showcoordinates true"))

def main():
    validate()
    for e in E:
        e["icon"] = iconof(e)
        e["daily"] = daily(e)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    # stable ids
    for i, e in enumerate(E):
        e["id"] = "cmd%04d" % i
        if not e["note"]:
            e.pop("note")
        if not e["warn"]:
            e.pop("warn")
    chat = sum(1 for e in E if e["cat"] == "chat")
    print("total", len(E), "chat", chat, "block", len(E) - chat)
    io.open(OUT, "w", encoding="utf-8").write(json.dumps({"c": E}, ensure_ascii=False, separators=(",", ":")))

if __name__ == "__main__":
    main()
