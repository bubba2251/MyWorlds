package com.bergerkiller.bukkit.mw;

import java.util.Iterator;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import com.bergerkiller.bukkit.common.collections.EntityMap;
import com.bergerkiller.bukkit.common.conversion.Conversion;
import com.bergerkiller.bukkit.common.events.CreaturePreSpawnEvent;
import com.bergerkiller.bukkit.common.reflection.classes.EntityRef;
import com.bergerkiller.bukkit.common.utils.CommonUtil;
import com.bergerkiller.bukkit.common.utils.PlayerUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;

public class MWListener implements Listener {
	// A mapping of player positions to store the actually entered portal
	private final EntityMap<Player, Location> playerPortalEnter = new EntityMap<Player, Location>();
	// Keeps track of player teleports
	private final TeleportationTracker teleportTracker = new TeleportationTracker();

	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldLoad(WorldLoadEvent event) {
		WorldConfig.get(event.getWorld()).timeControl.updateWorld(event.getWorld());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onWorldUnload(WorldUnloadEvent event) {
		WorldConfig.get(event.getWorld()).onWorldUnload(event.getWorld());
		WorldManager.closeWorldStreams(event.getWorld());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldInit(WorldInitEvent event) {
		if (MyWorlds.plugin.clearInitDisableSpawn(event.getWorld().getName())) {
			WorldUtil.setKeepSpawnInMemory(event.getWorld(), false);
		} else {
			WorldConfig.get(event.getWorld()).onWorldLoad(event.getWorld());
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onWeatherChange(WeatherChangeEvent event) {
		if (!MyWorlds.plugin.ignoreWeatherChanges && WorldConfig.get(event.getWorld()).holdWeather) {
			event.setCancelled(true);
		} else {
			WorldConfig.get(event.getWorld()).updateSpoutWeather(event.getWorld());
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerJoin(PlayerJoinEvent event) {
		WorldConfig.get(event.getPlayer()).update(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerRespawn(PlayerRespawnEvent event) {
		// Update spawn position based on world configuration
		org.bukkit.World respawnWorld = event.getPlayer().getWorld();
		if (MyWorlds.forceMainWorldSpawn) {
			// Force a respawn on the main world
			respawnWorld = MyWorlds.getMainWorld();
		} else if (event.isBedSpawn() && !WorldConfig.get(event.getPlayer()).forcedRespawn) {
			respawnWorld = null; // Ignore bed spawns that are not overrided
		}
		if (respawnWorld != null) {
			Location loc = WorldManager.getRespawnLocation(respawnWorld);
			if (loc != null) {
				event.setRespawnLocation(loc);
			}
		}
		WorldConfig.get(event.getRespawnLocation()).update(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerQuit(PlayerQuitEvent event) {
		// Reload the world if the player just exited a world to be reloaded
		WorldConfig.updateReload(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerMove(PlayerMoveEvent event) {
		// Handle player movement for portals
		teleportTracker.updatePlayerPosition(event.getPlayer(), event.getTo());

		// Water teleportation handling
		Block b = event.getTo().getBlock();
		final int statid = Material.STATIONARY_WATER.getId(); // = 9
		if (MyWorlds.useWaterTeleport && b.getTypeId() == statid) {
			if (b.getRelative(BlockFace.UP).getTypeId() == statid || b.getRelative(BlockFace.DOWN).getTypeId() == statid) {
				boolean allow = false;
				if (b.getRelative(BlockFace.NORTH).getType() == Material.AIR || b.getRelative(BlockFace.SOUTH).getType() == Material.AIR) {
					if (Util.isSolid(b, BlockFace.WEST) && Util.isSolid(b, BlockFace.EAST)) {
						allow = true;
					}
				} else if (b.getRelative(BlockFace.EAST).getType() == Material.AIR || b.getRelative(BlockFace.WEST).getType() == Material.AIR) {
					if (Util.isSolid(b, BlockFace.NORTH) && Util.isSolid(b, BlockFace.SOUTH)) {
						allow = true;
					}
				}
				if (allow && teleportTracker.canTeleport(event.getPlayer())) {
					Portal.handlePortalEnter(event.getPlayer(), Material.STATIONARY_WATER);
				}
			}
		}

		// Reload the world if the player just exited a world to be reloaded
		if (event.getFrom().getWorld() != event.getTo().getWorld()) {
			WorldConfig.updateReload(event.getFrom());
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerTeleport(PlayerTeleportEvent event) {
		teleportTracker.setPortalPoint(event.getPlayer(), event.getTo());
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerPortal(PlayerPortalEvent event) {
		final boolean nether = event.getCause() == TeleportCause.NETHER_PORTAL;
		final boolean end = event.getCause() == TeleportCause.END_PORTAL;
		if (!nether && !end) {
			return; // Ignore alternative types
		}
		// Cancel the internal logic
		event.setCancelled(true);

		// Get from location
		Location enterLoc = playerPortalEnter.remove(event.getPlayer());
		if (enterLoc == null) {
			enterLoc = event.getFrom();
		}
		Block b = enterLoc.getBlock();

		// Handle player teleportation - portal check
		Material mat = Material.AIR;
		if (nether) {
			mat = Material.PORTAL;
			if (!Util.isNetherPortal(b, true)) {
				return; // Invalid
			}
		} else if (end) {
			mat = Material.ENDER_PORTAL;
			if (!Util.isEndPortal(b, true)) {
				return; // Invalid
			}
		}

		// Perform teleportation
		if (teleportTracker.canTeleport(event.getPlayer())) {
			teleportTracker.setPortalPoint(event.getPlayer(), enterLoc);
			Object loc = Portal.getPortalEnterDestination(event.getPlayer(), mat);
			Location dest = null;
			if (loc instanceof Portal) {
				dest = ((Portal) loc).getDestination();
				if (dest == null) {
					String name = ((Portal) loc).getDestinationName();
					if (name != null) {
						// Show message indicating the destination is unavailable
						Localization.PORTAL_NOTFOUND.message(event.getPlayer(), name);
					}
				}
			} else if (loc instanceof Location) {
				dest = (Location) loc;
			}
			if (dest == null) {
				Localization.PORTAL_NODESTINATION.message(event.getPlayer());
			} else if (MWPermissionListener.handleTeleportPermission(event.getPlayer(), dest)) {
				// Only use the travel agent when not teleporting to fixed portals
				event.useTravelAgent(!(loc instanceof Portal));
				event.setCancelled(false);
				event.setTo(dest);
				// Send teleport message
				if (loc instanceof Portal) {
					Localization.PORTAL_ENTER.message(event.getPlayer(), ((Portal) loc).getDestinationDisplayName());
				} else if (dest.getWorld() != event.getPlayer().getWorld()) {
					Localization.WORLD_ENTER.message(event.getPlayer(), dest.getWorld().getName());
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onEntityPortalEnter(EntityPortalEnterEvent event) {
		if (event.getEntity() instanceof Player) {
			Player player = (Player) event.getEntity();

			// If player is creative, we can instantly handle the teleport in PLAYER_PORTAL_ENTER
			// If not but the delayed teleportation is preferred, then also let PLAYER_PORTAL_ENTER handle it
			// If not, then we will handle the teleportation in here
			if (PlayerUtil.isInvulnerable(player) || !MyWorlds.alwaysInstantPortal) {
				// Store the to location - the one in the PLAYER_PORTAL_ENTER is inaccurate
				playerPortalEnter.put(player, event.getLocation());
				// Ignore teleportation here, handle it during PLAYER_PORTAL_ENTER
				return;
			}

			// We are about to handle teleportation here...be sure to avoid spam
			if (teleportTracker.canTeleport(player)) {
				teleportTracker.setPortalPoint(player, event.getLocation());
			} else {
				return;
			}
		} else if (MyWorlds.onlyPlayerTeleportation) {
			return; // Ignore
		}
		// Handle teleportation
		Block b = event.getLocation().getBlock();
		if (!Util.isNetherPortal(b, false) && !Util.isEndPortal(b, false)) {
			return;
		}
		Portal.handlePortalEnter(event.getEntity(), b.getType());
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerChat(AsyncPlayerChatEvent event) {
		// Handle chat permissions
		if (!Permission.canChat(event.getPlayer())) {
			event.setCancelled(true);
			Localization.WORLD_NOCHATACCESS.message(event.getPlayer());
			return;
		}
		Iterator<Player> iterator = event.getRecipients().iterator();
		while (iterator.hasNext()) {
			if (!Permission.canChat(event.getPlayer(), iterator.next())) {
				iterator.remove();
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerChangedWorld(final PlayerChangedWorldEvent event) {
		WorldConfig.updateReload(event.getFrom());
		WorldConfig.get(event.getPlayer()).update(event.getPlayer());
		// Execute it again the next tick to ensure changes happened
		CommonUtil.nextTick(new Runnable() {
			public void run() {
				WorldConfig.get(event.getPlayer()).update(event.getPlayer());
			}
		});
		if (MyWorlds.useWorldInventories && !Permission.GENERAL_KEEPINV.has(event.getPlayer())) {
			Object playerHandle = Conversion.toEntityHandle.convert(event.getPlayer());
			org.bukkit.World newWorld = EntityRef.world.get(playerHandle);
			EntityRef.world.set(playerHandle, event.getFrom());
			CommonUtil.savePlayer(event.getPlayer());
			EntityRef.world.set(playerHandle, newWorld);
			MWPlayerDataController.refreshState(event.getPlayer());
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onCreatureSpawn(CreatureSpawnEvent event) {
		// Any creature spawns we couldn't cancel in PreSpawn we will cancel here
		if (event.getSpawnReason() != SpawnReason.CUSTOM && (!MyWorlds.ignoreEggSpawns || event.getSpawnReason() != SpawnReason.SPAWNER_EGG)) {
			if (WorldConfig.get(event.getEntity()).spawnControl.isDenied(event.getEntity())) {
				event.setCancelled(true);
			}
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onCreaturePreSpawn(CreaturePreSpawnEvent event) {
		// Cancel creature spawns before entities are spawned
		if (WorldConfig.get(event.getSpawnLocation()).spawnControl.isDenied(event.getEntityType())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onBlockForm(BlockFormEvent event) {
		// Snow/Ice forming cancelling based on world settings
		Material type = event.getNewState().getType();
		if (type == Material.SNOW) {
			if (!WorldConfig.get(event.getBlock()).formSnow) {
				event.setCancelled(true);
			}
		} else if (type == Material.ICE) {
			if (!WorldConfig.get(event.getBlock()).formIce) {
				event.setCancelled(true);
			}
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onBlockPhysics(BlockPhysicsEvent event) {
		// Allows portals to be placed without physics killing it
		if (event.getBlock().getType() == Material.PORTAL) {
			if (!(event.getBlock().getRelative(BlockFace.DOWN).getType() == Material.AIR)) {
				event.setCancelled(true);
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event) {
		Portal portal = Portal.get(event.getBlock(), false);
		if (portal != null && portal.remove()) {
			event.getPlayer().sendMessage(ChatColor.RED + "You removed portal " + ChatColor.WHITE + portal.getName() + ChatColor.RED + "!");
			MyWorlds.plugin.logAction(event.getPlayer(), "Removed portal '" + portal.getName() + "'!");
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onSignChange(SignChangeEvent event) {
		Portal portal = Portal.get(event.getBlock(), event.getLines());
		if (portal != null) {
			if (Permission.PORTAL_CREATE.has(event.getPlayer())) {
				if (Portal.exists(event.getPlayer().getWorld().getName(), portal.getName())) {
					if (!MyWorlds.allowPortalNameOverride || !Permission.PORTAL_OVERRIDE.has(event.getPlayer())) {
						event.getPlayer().sendMessage(ChatColor.RED + "This portal name is already used!");
						event.setCancelled(true);
						return;
					}
				}
				portal.add();
				MyWorlds.plugin.logAction(event.getPlayer(), "Created a new portal: '" + portal.getName() + "'!");
				// Build message
				if (portal.getDestinationName() != null) {
					Localization.PORTAL_CREATE_TO.message(event.getPlayer(), portal.getDestinationName());
					if (!portal.hasDestination()) {
						Localization.PORTAL_CREATE_MISSING.message(event.getPlayer());
					}
				} else {
					Localization.PORTAL_CREATE_END.message(event.getPlayer());
				}
			} else {
				event.setCancelled(true);
			}
		}
	}
}
