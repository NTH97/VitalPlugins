package net.vital.plugins.walker;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import vital.api.Bridge;
import vital.api.entities.Players;
import vital.api.entities.Tiles;
import vital.api.entities.TileObject;
import vital.api.input.Movement;
import vital.api.world.Pathfinder;

@Slf4j
@PluginDescriptor(
	name = "Walker Test",
	description = "Walk to any world coordinate via pathfinding",
	tags = {"walk", "pathfinder", "movement"}
)
public class WalkerPlugin extends Plugin
{
	private static final int ARRIVAL_THRESHOLD = 3;
	private static final int IDLE_TICKS_BEFORE_STEP = 2;
	private static final int STUCK_TICKS = 10;
	private static final int DEVIATION_THRESHOLD = 15;
	private static final int STRIDE = 4; // x, y, plane, transport per tile
	private static final int TRANSPORT_APPROACH_DIST = 3;
	private static final int TRANSPORT_TIMEOUT_TICKS = 10;

	@Inject
	private WalkerConfig config;

	private int destWorldX, destWorldY, destPlane;
	private boolean walking;
	private String lastParsedDest = "";
	private int[] globalPath;
	private int pathIndex;
	private int lastPlayerX, lastPlayerY;
	private int idleTicks;
	private int stuckTicks;

	// Plane transition state
	private boolean planeTransitioning;
	private int planeTransportSrcX, planeTransportSrcY;
	private int planeTransportDstX, planeTransportDstY;
	private boolean waitingForPlaneChange;
	private int planeChangeWaitTicks;

	// Transport interaction state
	private boolean waitingForTransport;
	private int transportDstX, transportDstY;
	private int transportWaitTicks;

	@Provides
	WalkerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WalkerConfig.class);
	}

	@Override
	protected void startUp()
	{
		reset();
		log.info("Walker started");
	}

	@Override
	protected void shutDown()
	{
		reset();
		log.info("Walker stopped");
	}

	private void reset()
	{
		walking = false;
		clearPathHighlight();
		globalPath = null;
		pathIndex = 0;
		idleTicks = 0;
		stuckTicks = 0;
		lastParsedDest = "";
		planeTransitioning = false;
		waitingForPlaneChange = false;
		planeChangeWaitTicks = 0;
		waitingForTransport = false;
		transportWaitTicks = 0;
	}

	private void clearPathHighlight()
	{
		Pathfinder.clearPathOverlay();
	}

	private boolean parseDestination()
	{
		String dest = config.destination().trim();
		if (dest.isEmpty())
		{
			if (walking)
			{
				log.info("[Walker] Destination cleared, stopping");
				walking = false;
			}
			lastParsedDest = "";
			return false;
		}

		if (dest.equals(lastParsedDest))
		{
			return walking;
		}

		String[] parts = dest.split("\\s+");
		if (parts.length < 2)
		{
			log.warn("[Walker] Invalid format, expected: worldX worldY [plane]");
			return false;
		}

		try
		{
			destWorldX = Integer.parseInt(parts[0]);
			destWorldY = Integer.parseInt(parts[1]);
			destPlane = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
		}
		catch (NumberFormatException e)
		{
			log.warn("[Walker] Invalid coordinates: {}", dest);
			return false;
		}

		lastParsedDest = dest;
		walking = true;
		clearPathHighlight();
		globalPath = null;
		stuckTicks = 0;
		idleTicks = 0;
		lastPlayerX = -1;
		lastPlayerY = -1;
		planeTransitioning = false;
		waitingForPlaneChange = false;
		log.info("[Walker] Walking to {} {} plane {}", destWorldX, destWorldY, destPlane);
		return true;
	}

	@Subscribe
	public void onGameTick(GameTick event) {

		if (!parseDestination())
		{
			return;
		}

		int localIdx = Players.getLocalPlayerIndex();
		if (localIdx < 0)
		{
			return;
		}

		var localPlayer = Players.getLocalPlayer();
		var localPlayerLocation = localPlayer.getWorldPoint();

		var x = localPlayerLocation.getX();
		var y = localPlayerLocation.getY();
		var plane = localPlayerLocation.getPlane();

		int[] playerWorld = Tiles.sceneToWorld(x, y);

		// Handle plane transitions
		if (plane != destPlane)
		{
			handlePlaneTransition(playerWorld, plane);
			return;
		}

		// We're on the correct plane — clear any plane transition state
		if (planeTransitioning || waitingForPlaneChange)
		{
			log.info("[Walker] Arrived on plane {}, resuming normal pathfinding", plane);
			planeTransitioning = false;
			waitingForPlaneChange = false;
			planeChangeWaitTicks = 0;
			globalPath = null; // Re-pathfind on the new plane
		}

		// Compute global path if needed
		if (globalPath == null)
		{
			globalPath = Pathfinder.findPath(playerWorld[0], playerWorld[1], destWorldX, destWorldY, destPlane);
			if (globalPath == null)
			{
				log.warn("[Walker] No global path found to {} {}", destWorldX, destWorldY);
				walking = false;
				return;
			}
			pathIndex = 0;
			updatePathOverlay(playerWorld[0], playerWorld[1]);
			log.info("[Walker] Global path computed: {} tiles", globalPath.length / STRIDE);
		}

		// Refresh overlay base each tick (scene base shifts on region crossings)
		updatePathOverlay(playerWorld[0], playerWorld[1]);

		// Check arrival
		int finalX = globalPath[globalPath.length - STRIDE];
		int finalY = globalPath[globalPath.length - STRIDE + 1];
		int dx = Math.abs(playerWorld[0] - finalX);
		int dy = Math.abs(playerWorld[1] - finalY);
		if (dx <= ARRIVAL_THRESHOLD && dy <= ARRIVAL_THRESHOLD)
		{
			log.info("[Walker] Arrived at {} {}", destWorldX, destWorldY);
			walking = false;
			clearPathHighlight();
			globalPath = null;
			return;
		}

		// Check if still moving
		if (Players.isMoving(localIdx))
		{
			lastPlayerX = playerWorld[0];
			lastPlayerY = playerWorld[1];
			idleTicks = 0;
			stuckTicks = 0;
			return;
		}

		// Idle wait
		idleTicks++;
		if (idleTicks < IDLE_TICKS_BEFORE_STEP)
		{
			return;
		}

		// Stuck detection
		if (playerWorld[0] == lastPlayerX && playerWorld[1] == lastPlayerY)
		{
			stuckTicks++;
			if (stuckTicks > STUCK_TICKS)
			{
				log.warn("[Walker] Stuck at {} {}, stopping", playerWorld[0], playerWorld[1]);
				walking = false;
				clearPathHighlight();
				globalPath = null;
				return;
			}
		}
		else
		{
			stuckTicks = 0;
		}

		// Advance pathIndex: find closest point on path to player
		int bestDist = Integer.MAX_VALUE;
		int bestIdx = pathIndex;
		int scanLimit = Math.min(globalPath.length - (STRIDE - 1), pathIndex + 20 * STRIDE);
		for (int i = pathIndex; i < scanLimit; i += STRIDE)
		{
			int pdx = playerWorld[0] - globalPath[i];
			int pdy = playerWorld[1] - globalPath[i + 1];
			int dist = pdx * pdx + pdy * pdy;
			if (dist < bestDist)
			{
				bestDist = dist;
				bestIdx = i;
			}
		}
		pathIndex = bestIdx;

		// Wait for transport interaction to complete
		if (waitingForTransport)
		{
			transportWaitTicks++;
			int distToDst = Math.max(Math.abs(playerWorld[0] - transportDstX), Math.abs(playerWorld[1] - transportDstY));
			if (distToDst <= TRANSPORT_APPROACH_DIST)
			{
				log.info("[Walker] Transport complete, arrived near ({},{})", transportDstX, transportDstY);
				waitingForTransport = false;
				transportWaitTicks = 0;
				globalPath = null; // Re-pathfind from new position
				return;
			}
			if (transportWaitTicks > TRANSPORT_TIMEOUT_TICKS)
			{
				log.warn("[Walker] Transport timed out after {} ticks, retrying", transportWaitTicks);
				waitingForTransport = false;
				transportWaitTicks = 0;
				// Fall through to retry interaction
			}
			else
			{
				return; // Still waiting
			}
		}

		// Check for upcoming transport tiles
		int walkToTransportX = -1;
		int walkToTransportY = -1;
		for (int i = pathIndex; i < Math.min(globalPath.length - (STRIDE - 1), pathIndex + 15 * STRIDE); i += STRIDE)
		{
			if (globalPath[i + 3] == 1)
			{
				// Found a transport destination tile — source is the tile before it
				int srcIdx = i - STRIDE;
				if (srcIdx >= 0)
				{
					int tSrcX = globalPath[srcIdx];
					int tSrcY = globalPath[srcIdx + 1];
					int distToSrc = Math.max(Math.abs(playerWorld[0] - tSrcX), Math.abs(playerWorld[1] - tSrcY));

					if (distToSrc <= TRANSPORT_APPROACH_DIST)
					{
						int tDstX = globalPath[i];
						int tDstY = globalPath[i + 1];
						if (interactWithTransport(tSrcX, tSrcY, tDstX, tDstY, plane))
						{
							waitingForTransport = true;
							transportDstX = tDstX;
							transportDstY = tDstY;
							transportWaitTicks = 0;
						}
						lastPlayerX = playerWorld[0];
						lastPlayerY = playerWorld[1];
						idleTicks = 0;
						return;
					}
					else
					{
						// Too far — walk toward the transport source
						walkToTransportX = tSrcX;
						walkToTransportY = tSrcY;
					}
				}
				break;
			}
		}

		// Pick walk target: transport source if one is ahead, otherwise random steps ahead
		int targetWorldX;
		int targetWorldY;
		if (walkToTransportX >= 0)
		{
			targetWorldX = walkToTransportX;
			targetWorldY = walkToTransportY;
		}
		else
		{
			int minStep = config.minStepDistance();
			int maxStep = config.maxStepDistance();
			int stepsAhead = (int) (Math.random() * (maxStep - minStep + 1)) + minStep;
			int targetIdx = Math.min(pathIndex + stepsAhead * STRIDE, globalPath.length - STRIDE);
			targetWorldX = globalPath[targetIdx];
			targetWorldY = globalPath[targetIdx + 1];
		}

		// Convert to scene coords and walk
		int[] sceneTarget = Tiles.worldToScene(targetWorldX, targetWorldY);
		Movement.walkTo(sceneTarget[0], sceneTarget[1]);

		lastPlayerX = playerWorld[0];
		lastPlayerY = playerWorld[1];
		idleTicks = 0;

		// Deviation check: re-pathfind if too far off path
		int devDx = playerWorld[0] - globalPath[pathIndex];
		int devDy = playerWorld[1] - globalPath[pathIndex + 1];
		double deviation = Math.sqrt((double) devDx * devDx + (double) devDy * devDy);
		if (deviation > DEVIATION_THRESHOLD)
		{
			log.info("[Walker] Deviated {} tiles from path, re-pathfinding", (int) deviation);
			clearPathHighlight();
			globalPath = null;
		}
	}

	private void handlePlaneTransition(int[] playerWorld, int currentPlane)
	{
		// If waiting for plane change after interacting with transport
		if (waitingForPlaneChange)
		{
			planeChangeWaitTicks++;
			if (planeChangeWaitTicks > TRANSPORT_TIMEOUT_TICKS)
			{
				log.warn("[Walker] Plane change timed out, retrying");
				waitingForPlaneChange = false;
				planeTransitioning = false;
				globalPath = null;
			}
			return;
		}

		// Find nearest transport to change planes
		// For multi-floor transitions (e.g., plane 2 -> 0), go one step at a time
		int nextPlane = currentPlane + (destPlane > currentPlane ? 1 : -1);

		int[] transport = Pathfinder.findPlaneTransport(playerWorld[0], playerWorld[1], currentPlane, nextPlane);
		if (transport == null)
		{
			log.warn("[Walker] No transport found from plane {} to plane {}", currentPlane, nextPlane);
			walking = false;
			return;
		}

		planeTransportSrcX = transport[0];
		planeTransportSrcY = transport[1];
		planeTransportDstX = transport[2];
		planeTransportDstY = transport[3];
		planeTransitioning = true;

		// Check if close enough to interact
		int distToSrc = Math.max(Math.abs(playerWorld[0] - planeTransportSrcX), Math.abs(playerWorld[1] - planeTransportSrcY));

		if (distToSrc <= TRANSPORT_APPROACH_DIST)
		{
			// Close enough — interact with the transport
			if (interactWithTransport(planeTransportSrcX, planeTransportSrcY, planeTransportDstX, planeTransportDstY, currentPlane))
			{
				waitingForPlaneChange = true;
				planeChangeWaitTicks = 0;
				log.info("[Walker] Interacting with plane transport at ({},{}) -> ({},{})",
					planeTransportSrcX, planeTransportSrcY, planeTransportDstX, planeTransportDstY);
			}
			else
			{
				log.warn("[Walker] Failed to interact with plane transport at ({},{})", planeTransportSrcX, planeTransportSrcY);
				walking = false;
			}
			return;
		}

		// Need to pathfind to the transport source on the current plane
		if (globalPath == null || !planeTransitioning)
		{
			globalPath = Pathfinder.findPath(playerWorld[0], playerWorld[1], planeTransportSrcX, planeTransportSrcY, currentPlane);
			if (globalPath == null)
			{
				log.warn("[Walker] No path to plane transport at ({},{})", planeTransportSrcX, planeTransportSrcY);
				walking = false;
				return;
			}
			pathIndex = 0;
			updatePathOverlay(playerWorld[0], playerWorld[1]);
			log.info("[Walker] Pathfinding to plane transport: {} tiles", globalPath.length / STRIDE);
		}

		// Walk toward the transport (normal walking logic)
		if (Players.isMoving(Players.getLocalPlayerIndex()))
		{
			return;
		}

		idleTicks++;
		if (idleTicks < IDLE_TICKS_BEFORE_STEP)
		{
			return;
		}

		// Advance pathIndex
		int bestDist = Integer.MAX_VALUE;
		int bestIdx = pathIndex;
		int scanLimit = Math.min(globalPath.length - (STRIDE - 1), pathIndex + 20 * STRIDE);
		for (int i = pathIndex; i < scanLimit; i += STRIDE)
		{
			int pdx = playerWorld[0] - globalPath[i];
			int pdy = playerWorld[1] - globalPath[i + 1];
			int dist = pdx * pdx + pdy * pdy;
			if (dist < bestDist)
			{
				bestDist = dist;
				bestIdx = i;
			}
		}
		pathIndex = bestIdx;

		int minStep = config.minStepDistance();
		int maxStep = config.maxStepDistance();
		int stepsAhead = (int) (Math.random() * (maxStep - minStep + 1)) + minStep;
		int targetIdx = Math.min(pathIndex + stepsAhead * STRIDE, globalPath.length - STRIDE);
		int targetWorldX = globalPath[targetIdx];
		int targetWorldY = globalPath[targetIdx + 1];

		int[] sceneTarget = Tiles.worldToScene(targetWorldX, targetWorldY);
		Movement.walkTo(sceneTarget[0], sceneTarget[1]);
		idleTicks = 0;
	}

	private boolean interactWithTransport(int srcX, int srcY, int dstX, int dstY, int plane)
	{
		String[] transportData = Bridge.getTransportAt(srcX, srcY, plane);
		if (transportData == null || transportData.length < 3)
		{
			log.warn("[Walker] No transport data at ({},{})", srcX, srcY);
			return false;
		}

		String action = transportData[0];
		int objectId;
		try
		{
			objectId = Integer.parseInt(transportData[2]);
		}
		catch (NumberFormatException e)
		{
			log.warn("[Walker] Invalid objectId: {}", transportData[2]);
			return false;
		}

		log.info("[Walker] Transport interact objectId={} action='{}' at ({},{}) -> ({},{})",
			objectId, action, srcX, srcY, dstX, dstY);

		return TileObject.interactAt(objectId, action, srcX, srcY);
	}

	// Strip transport flags from path (stride 4 -> stride 3) for the overlay
	private void updatePathOverlay(int playerWorldX, int playerWorldY)
	{
		if (globalPath == null)
		{
			return;
		}
		int tileCount = globalPath.length / STRIDE;
		int[] xyzPath = new int[tileCount * 3];
		for (int i = 0; i < tileCount; i++)
		{
			xyzPath[i * 3] = globalPath[i * STRIDE];
			xyzPath[i * 3 + 1] = globalPath[i * STRIDE + 1];
			xyzPath[i * 3 + 2] = globalPath[i * STRIDE + 2];
		}
		Pathfinder.setPathOverlay(xyzPath, playerWorldX, playerWorldY);
	}
}
