/*
 *  Copyright 2011 Benjamin Glatzel <benjamin.glatzel@me.com>.
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  under the License.
 */
package com.github.begla.blockmania;

import com.github.begla.blockmania.generators.Generator;
import com.github.begla.blockmania.generators.GeneratorForest;
import com.github.begla.blockmania.generators.GeneratorTerrain;
import com.github.begla.blockmania.utilities.FastRandom;
import com.github.begla.blockmania.utilities.Helper;
import com.github.begla.blockmania.utilities.RayFaceIntersection;
import java.io.IOException;
import static org.lwjgl.opengl.GL11.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.lwjgl.util.vector.Vector3f;
import org.newdawn.slick.opengl.Texture;
import org.newdawn.slick.opengl.TextureLoader;
import org.newdawn.slick.util.ResourceLoader;

/**
 * The world of Blockmania. At its most basic the world contains chunks (consisting of a fixed amount of blocks)
 * and the player.
 *
 * The world is randomly generated by using some perlin noise generators initialized
 * with a favored seed value.
 * 
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public final class World extends RenderableObject {

    private double _statUpdateDuration = 0.0f;
    /* ------ */
    private short _time = 17;
    private long lastDaytimeMeasurement = Helper.getInstance().getTime();
    /* ------ */
    private final FastRandom _rand;
    /* ------ */
    private static Texture _textureSun;
    /* ------ */
    private byte _daylight = 16;
    private Player _player;
    /* ------ */
    private boolean _updatingEnabled = false;
    private final Thread _updateThread;
    /* ------ */
    private Chunk[][][] _chunks;
    /* ------ */
    private final List<Chunk> _chunkUpdateQueueDL = Collections.synchronizedList(new LinkedList<Chunk>());
    private final List<Chunk> _chunkUpdateNormal = Collections.synchronizedList(new LinkedList<Chunk>());
    private final Map<Integer, Chunk> _chunkCache = Collections.synchronizedMap(new TreeMap<Integer, Chunk>());
    /* ------ */
    private final GeneratorTerrain _generatorTerrain;
    private final GeneratorForest _generatorForest;

    /**
     * Initializes a new world for the single player mode.
     * 
     * @param title The title/description of the world
     * @param seed The seed string used to genrate the terrain
     * @param p The player
     */
    public World(String title, String seed, Player p) {
        this._player = p;
        _rand = new FastRandom(seed.hashCode());
        _chunks = new Chunk[(int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x][(int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y][(int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.z];

        // Init. generators
        _generatorTerrain = new GeneratorTerrain(seed);
        _generatorForest = new GeneratorForest(seed);

        for (int x = 0; x < Configuration.VIEWING_DISTANCE_IN_CHUNKS.x; x++) {
            for (int z = 0; z < Configuration.VIEWING_DISTANCE_IN_CHUNKS.z; z++) {
                Chunk c = loadOrCreateChunk(x, z);
                _chunks[x][0][z] = c;
                queueChunkForUpdate(c);
            }
        }

        _player.resetPlayer();

        _updateThread = new Thread(new Runnable() {

            @Override
            public void run() {
                while (true) {
                    if (!_updatingEnabled) {
                        synchronized (_updateThread) {
                            try {
                                _updateThread.wait();
                            } catch (InterruptedException ex) {
                                Logger.getLogger(World.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }
                    }

                    long timeStart = System.currentTimeMillis();
                    timeStart = System.currentTimeMillis();

                    if (!_chunkUpdateNormal.isEmpty()) {
                        Chunk[] chunks = _chunkUpdateNormal.toArray(new Chunk[0]);

                        // Find the closest chunk
                        double dist = Float.MAX_VALUE;
                        int index = -1;

                        for (int i = 0; i < chunks.length; i++) {
                            Chunk c = chunks[i];
                            double tDist = c.calcDistanceToPlayer();

                            if (tDist <= dist) {
                                dist = tDist;
                                index = i;
                            }
                        }

                        if (index >= 0) {
                            Chunk c = (Chunk) chunks[index];
                            processChunk(c);
                            _chunkUpdateNormal.remove(c);
                        }
                        _statUpdateDuration += System.currentTimeMillis() - timeStart;
                        _statUpdateDuration /= 2;
                    }

                    updateInfWorld();
                    updateDaytime();
                }
            }
        });

    }

    /**
     * Processes a chunk. This method is used within the update thread
     * and updates the lighting and vertex arrays of a chunk and its
     * neighbors based on their dirty flags.
     *
     * @param c The chunk to process
     */
    private void processChunk(Chunk c) {
        if (c != null) {
            if (!c.generate() && c._lightDirty) {
                c.calcLight();
            }
            Chunk[] neighbors = c.getNeighbors();
            for (Chunk nc : neighbors) {
                if (nc != null) {
                    if (!nc.generate() && nc._lightDirty) {
                        nc.calcLight();
                    }
                    if (nc._dirty) {
                        nc.generateVertexArrays();
                        _chunkUpdateQueueDL.add(nc);
                    }
                }
            }
            if (c._dirty) {
                c.generateVertexArrays();
                _chunkUpdateQueueDL.add(c);
            }
        }
    }

    /**
     * Updates the daytime of the world. A day in Blockmania takes 12 minutes.
     */
    private void updateDaytime() {
        if (Helper.getInstance().getTime() - lastDaytimeMeasurement >= 30000) {
            _time = (short) ((_time + 1) % 24);
            lastDaytimeMeasurement = Helper.getInstance().getTime();

            Logger.getLogger(World.class.getName()).log(Level.INFO, "Updated daytime to {0}h.", _time);

            byte oldDaylight = _daylight;

            if (_time >= 18 && _time < 20) {
                _daylight = (byte) (0.8f * Configuration.MAX_LIGHT);
            } else if (_time == 20) {
                _daylight = (byte) (0.6f * Configuration.MAX_LIGHT);
            } else if (_time == 21) {
                _daylight = (byte) (0.4f * Configuration.MAX_LIGHT);
            } else if (_time == 22) {
                _daylight = (byte) (0.3f * Configuration.MAX_LIGHT);
            } else if (_time >= 0 && _time <= 5) {
                _daylight = (byte) (0.2f * Configuration.MAX_LIGHT);
            } else if (_time == 6) {
                _daylight = (byte) (0.3f * Configuration.MAX_LIGHT);
            } else if (_time == 7) {
                _daylight = (byte) (0.6f * Configuration.MAX_LIGHT);
            } else if (_time >= 8 && _time < 18) {
                _daylight = (byte) Configuration.MAX_LIGHT;
            }

            // Only update the chunks if the daylight value has changed
            if (_daylight != oldDaylight) {
                markCachedChunksDirty(false);
                updateAllChunks();
            }
        }
    }

    /**
     * Updates the displayed chunks according to the players position and queues
     * new chunks for updating.
     */
    private void updateInfWorld() {

        for (int x = 0; x < Configuration.VIEWING_DISTANCE_IN_CHUNKS.x; x++) {
            for (int z = 0; z < Configuration.VIEWING_DISTANCE_IN_CHUNKS.z; z++) {
                Chunk c = getChunk(x, 0, z);
                if (c != null) {
                    Vector3f pos = new Vector3f(x, 0, z);
                    int multZ = (int) calcPlayerChunkOffsetZ() / (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.z + 1;
                    if (z < calcPlayerChunkOffsetZ() % Configuration.VIEWING_DISTANCE_IN_CHUNKS.z) {
                        pos.z += Configuration.VIEWING_DISTANCE_IN_CHUNKS.z * multZ;
                    } else {
                        pos.z += Configuration.VIEWING_DISTANCE_IN_CHUNKS.z * (multZ - 1);
                    }
                    int multX = (int) calcPlayerChunkOffsetX() / (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x + 1;
                    if (x < calcPlayerChunkOffsetX() % Configuration.VIEWING_DISTANCE_IN_CHUNKS.x) {
                        pos.x += Configuration.VIEWING_DISTANCE_IN_CHUNKS.x * multX;
                    } else {
                        pos.x += Configuration.VIEWING_DISTANCE_IN_CHUNKS.x * (multX - 1);
                    }
                    if (c.getPosition().x != pos.x || c.getPosition().z != pos.z) {
                        // Remove the old chunk from the chunk update queue
                        _chunkUpdateNormal.remove(c);
                        // Try to load a cached version of the chunk
                        c = loadOrCreateChunk((int) pos.x, (int) pos.z);
                        // Replace the old chunk
                        _chunks[x][0][z] = c;
                        // And queue it for updating
                        queueChunkForUpdate(c);
                    }
                }
            }
        }
    }

    /**
     * Queues all displayed chunks for updating.
     */
    public void updateAllChunks() {
        for (int x = 0; x < Configuration.VIEWING_DISTANCE_IN_CHUNKS.x; x++) {
            for (int y = 0; y < Configuration.VIEWING_DISTANCE_IN_CHUNKS.y; y++) {
                for (int z = 0; z < Configuration.VIEWING_DISTANCE_IN_CHUNKS.z; z++) {
                    Chunk c = getChunk(x, y, z);
                    queueChunkForUpdate(c);
                }
            }
        }
    }

    /**
     * Static initialization of a chunk. For example, this
     * method loads the texture of the sun.
     */
    public static void init() {
        try {
            Logger.getLogger(World.class.getName()).log(Level.INFO, "Loading worLoggerld textures...");
            _textureSun = TextureLoader.getTexture("png", ResourceLoader.getResource("com/github/begla/blockmania/images/sun.png").openStream(), GL_NEAREST);
            Logger.getLogger(World.class.getName()).log(Level.INFO, "Finished loading world textures!");
        } catch (IOException ex) {
            Logger.getLogger(World.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Renders the world.
     */
    @Override
    public void render() {
        /**
         * Draws the sun.
         */
        glPushMatrix();
        // Position the sun relatively to the player
        glTranslatef(_player.getPosition().x, Configuration.VIEWING_DISTANCE_IN_CHUNKS.y * Configuration.CHUNK_DIMENSIONS.y * 0.75f, Configuration.VIEWING_DISTANCE_IN_CHUNKS.z * Configuration.CHUNK_DIMENSIONS.z + _player.getPosition().z);

        // Disable fog
        glDisable(GL_FOG);

        glColor4f(1f, 1f, 1f, 1.0f);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_TEXTURE_2D);
        _textureSun.bind();
        glBegin(GL_QUADS);
        glTexCoord2f(0.0f, 0.0f);
        glVertex3f(-Configuration.SUN_SIZE, Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(1.f, 0.0f);
        glVertex3f(Configuration.SUN_SIZE, Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(1.f, 1.0f);
        glVertex3f(Configuration.SUN_SIZE, -Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(0.f, 1.0f);
        glVertex3f(-Configuration.SUN_SIZE, -Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glEnd();
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);

        glEnable(GL_FOG);
        glPopMatrix();


        /**
         * Render all active chunks.
         */
        for (int x = 0; x < Configuration.VIEWING_DISTANCE_IN_CHUNKS.x; x++) {
            for (int z = 0; z < Configuration.VIEWING_DISTANCE_IN_CHUNKS.z; z++) {
                Chunk c = getChunk(x, 0, z);
                if (c != null) {
                    c.render(false);
                }
            }
        }
        for (int x = 0; x < Configuration.VIEWING_DISTANCE_IN_CHUNKS.x; x++) {
            for (int z = 0; z < Configuration.VIEWING_DISTANCE_IN_CHUNKS.z; z++) {
                Chunk c = getChunk(x, 0, z);
                if (c != null) {
                    c.render(true);
                }
            }
        }
    }

    /*
     * Updates the world. This method checks the queue for the display
     * list updates and recreates the display lists accordingly.
     */
    @Override
    public void update(long delta) {
        try {
            Chunk c = _chunkUpdateQueueDL.remove(0);
            c.generateDisplayList();
        } catch (Exception e) {
        }
    }

    /**
     * Genrates a simple tree at a given position.
     * 
     * @param posX X-coordinate
     * @param posY Y-coordinate
     * @param posZ Z-coordinate
     * @param update If set the affected chunks are queued for updating
     */
    public void generateTree(int posX, int posY, int posZ, boolean update) {

        int height = _rand.randomInt() % 2 + 6;

        // Generate tree trunk
        for (int i = 0; i < height; i++) {
            setBlock(posX, posY + i, posZ, (byte) 0x5, update);
        }

        // Generate the treetop
        for (int y = height - 2; y < height + 2; y += 1) {
            for (int x = -2; x < 3; x++) {
                for (int z = -2; z < 3; z++) {
                    if (!(x == -2 && z == -2) && !(x == 2 && z == 2) && !(x == -2 && z == 2) && !(x == 2 && z == -2)) {
                        if (_rand.randomDouble() <= 0.8f) {
                            setBlock(posX + x, posY + y, posZ + z, (byte) 0x6, update);
                        }
                    }
                }
            }
        }
    }

    /**
     * Genrates a simple pine tree at a given position.
     *
     * @param posX X-coordinate
     * @param posY Y-coordinate
     * @param posZ Z-coordinate
     * @param update If set the affected chunks are queued for updating
     */
    public void generatePineTree(int posX, int posY, int posZ, boolean update) {

        int height = _rand.randomInt() % 4 + 12;

        // Generate tree trunk
        for (int i = 0; i < height; i++) {
            setBlock(posX, posY + i, posZ, (byte) 0x5, update);
        }

        // Generate the treetop
        for (int y = 0; y < 10; y += 2) {
            for (int x = -5 + y / 2; x <= 5 - y / 2; x++) {
                for (int z = -5 + y / 2; z <= 5 - y / 2; z++) {
                    if (!(x == 0 && z == 0)) {
                        setBlock(posX + x, posY + y + (height - 10), posZ + z, (byte) 0x6, update);
                    }
                }
            }
        }
    }

    /**
     * Returns the chunk position of a given coordinate.
     *
     * @param x The X-coordinate of the block
     * @return The X-coordinate of the chunk
     */
    private int calcChunkPosX(int x) {
        return (x / (int) Configuration.CHUNK_DIMENSIONS.x);
    }

    /**
     * Returns the chunk position of a given coordinate.
     *
     * @param y The Y-coordinate of the block
     * @return The Y-coordinate of the vchunk
     */
    private int calcChunkPosY(int y) {
        return (y / (int) Configuration.CHUNK_DIMENSIONS.y);
    }

    /**
     * Returns the chunk position of a given coordinate.
     *
     * @param z The Z-coordinate of the block
     * @return The Z-coordinate of the chunk
     */
    private int calcChunkPosZ(int z) {
        return (z / (int) Configuration.CHUNK_DIMENSIONS.z);
    }

    /**
     * Returns the internal position of a block within a chunk.
     *
     * @param x1 The X-coordinate of the block within the world
     * @param x2 The X-coordinate of the chunk within the world
     * @return The X-coordinate of the block within the chunk
     */
    private int calcBlockPosX(int x1, int x2) {
        x1 = x1 % ((int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x * (int) Configuration.CHUNK_DIMENSIONS.x);
        return (x1 - (x2 * (int) Configuration.CHUNK_DIMENSIONS.x));
    }

    /**
     * Returns the internal position of a block within a chunk.
     *
     * @param x1 The Y-coordinate of the block within the world
     * @param x2 The Y-coordinate of the chunk within the world
     * @return The Y-coordinate of the block within the chunk
     */
    private int calcBlockPosY(int y1, int y2) {
        y1 = y1 % ((int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y * (int) Configuration.CHUNK_DIMENSIONS.y);
        return (y1 - (y2 * (int) Configuration.CHUNK_DIMENSIONS.y));
    }

    /**
     * Returns the internal position of a block within a chunk.
     *
     * @param x1 The Z-coordinate of the block within the world
     * @param x2 The Z-coordinate of the chunk within the world
     * @return The Z-coordinate of the block within the chunk
     */
    private int calcBlockPosZ(int z1, int z2) {
        z1 = z1 % ((int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.z * (int) Configuration.CHUNK_DIMENSIONS.z);
        return (z1 - (z2 * (int) Configuration.CHUNK_DIMENSIONS.z));
    }

    /**
     * Places a block of a specific type at a given position.
     * 
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @param type The type of the block to set
     * @param update If set the affected chunk is queued for updating
     */
    public final void setBlock(int x, int y, int z, byte type, boolean update) {
        int chunkPosX = calcChunkPosX(x) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x;
        int chunkPosY = calcChunkPosY(y) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y;
        int chunkPosZ = calcChunkPosZ(z) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.z;

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosY = calcBlockPosY(y, chunkPosY);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));
        c.setBlock(blockPosX, blockPosY, blockPosZ, type);

        // Queue the chunk for update
        if (update) {
            c.calcSunlightAtLocalPos(blockPosX, blockPosZ);
            queueChunkForUpdate(c);
        }
    }

    /**
     * Returns the chunk at the given position.
     * 
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @return The chunk
     */
    public final Chunk getChunk(int x, int y, int z) {
        Chunk c = null;

        try {
            c = _chunks[x % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x][y % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y][z % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.z];
        } catch (Exception e) {
        }

        return c;
    }

    /**
     * Returns the block at the given position.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @return The type of the block
     */
    public final byte getBlock(int x, int y, int z) {
        int chunkPosX = calcChunkPosX(x) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x;
        int chunkPosY = calcChunkPosY(y) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y;
        int chunkPosZ = calcChunkPosZ(z) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.z;

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosY = calcBlockPosY(y, chunkPosY);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = loadChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            return c.getBlock(blockPosX, blockPosY, blockPosZ);
        }

        return -1;
    }

    /**
     * Returns the light value at the given position.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @return The light value
     */
    public final byte getLight(int x, int y, int z) {
        int chunkPosX = calcChunkPosX(x) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x;
        int chunkPosY = calcChunkPosY(y) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y;
        int chunkPosZ = calcChunkPosZ(z) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.z;

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosY = calcBlockPosY(y, chunkPosY);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        Chunk c = loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

        if (c != null) {
            return c.getLight(blockPosX, blockPosY, blockPosZ);
        }

        return -1;
    }

    /**
     * Sets the light value at the given position.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @param intens The light intensity value
     */
    public void setLight(int x, int y, int z, byte intens) {
        int chunkPosX = calcChunkPosX(x) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x;
        int chunkPosY = calcChunkPosY(y) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y;
        int chunkPosZ = calcChunkPosZ(z) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.z;

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosY = calcBlockPosY(y, chunkPosY);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        try {
            Chunk c = loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));
            c.setLight(blockPosX, blockPosY, blockPosZ, intens);
        } catch (Exception e) {
        }
    }

    /**
     * Returns the daylight value.
     * 
     * @return The daylight value
     */
    public float getDaylightAsFloat() {
        return _daylight / 16f;
    }

    /**
     * Returns the player.
     * 
     * @return The player
     */
    public Player getPlayer() {
        return _player;
    }

    /**
     * Returns the color of the daylight as a vector.
     * 
     * @return The daylight color
     */
    public Vector3f getDaylightColor() {
        return new Vector3f(getDaylightAsFloat() * 0.55f, getDaylightAsFloat() * 0.85f, 0.99f * getDaylightAsFloat());
    }

    /**
     * Calculates the offset of the player relative to the spawning point.
     * 
     * @return The player offset on the x-axis
     */
    private int calcPlayerChunkOffsetX() {
        return (int) ((_player.getPosition().x - Helper.getInstance().calcPlayerOrigin().x) / Configuration.CHUNK_DIMENSIONS.x);
    }

    /**
     * Calculates the offset of the player relative to the spawning point.
     *
     * @return The player offset on the y-axis
     */
    private int calcPlayerChunkOffsetY() {
        return (int) ((_player.getPosition().y - Helper.getInstance().calcPlayerOrigin().y) / Configuration.CHUNK_DIMENSIONS.y);
    }

    /**
     * Calculates the offset of the player relative to the spawning point.
     *
     * @return The player offset on the z-axis
     */
    private int calcPlayerChunkOffsetZ() {
        return (int) ((_player.getPosition().z - Helper.getInstance().calcPlayerOrigin().z) / Configuration.CHUNK_DIMENSIONS.z);
    }

    /*
     * Returns the vertices of a block at the given position.
     */
    public Vector3f[] verticesForBlockAt(int x, int y, int z) {
        Vector3f[] vertices = new Vector3f[8];

        vertices[0] = new Vector3f(x - .5f, y - .5f, z - .5f);
        vertices[1] = new Vector3f(x + .5f, y - .5f, z - .5f);
        vertices[2] = new Vector3f(x + .5f, y + .5f, z - .5f);
        vertices[3] = new Vector3f(x - .5f, y + .5f, z - .5f);

        vertices[4] = new Vector3f(x - .5f, y - .5f, z + .5f);
        vertices[5] = new Vector3f(x + .5f, y - .5f, z + .5f);
        vertices[6] = new Vector3f(x + .5f, y + .5f, z + .5f);
        vertices[7] = new Vector3f(x - .5f, y + .5f, z + .5f);

        return vertices;
    }

    /**
     * Calculates the intersection of a given ray originating from a specified point with
     * a block. Returns a list of intersections ordered by the distance to the player.
     *
     * @return Distance-ordered list of ray-face-intersections
     */
    public ArrayList<RayFaceIntersection> rayBlockIntersection(int x, int y, int z, Vector3f origin, Vector3f ray) {
        /*
         * If the block is made out of air... panic and get out of here. Fast.
         */
        if (getBlock(x, y, z) == 0) {
            return null;
        }

        ArrayList<RayFaceIntersection> result = new ArrayList<RayFaceIntersection>();

        /**
         * Fetch all vertices of the specified block.
         */
        Vector3f[] vertices = verticesForBlockAt(x, y, z);
        Vector3f blockPos = new Vector3f(x, y, z);

        /*
         * Generate a new intersection for each side of the block.
         */

        // Front
        RayFaceIntersection is = rayFaceIntersection(blockPos, vertices[0], vertices[3], vertices[2], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Back
        is = rayFaceIntersection(blockPos, vertices[4], vertices[5], vertices[6], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Left
        is = rayFaceIntersection(blockPos, vertices[0], vertices[4], vertices[7], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Right
        is = rayFaceIntersection(blockPos, vertices[1], vertices[2], vertices[6], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Top
        is = rayFaceIntersection(blockPos, vertices[3], vertices[7], vertices[6], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Bottom
        is = rayFaceIntersection(blockPos, vertices[0], vertices[1], vertices[5], origin, ray);
        if (is != null) {
            result.add(is);
        }

        /*
         * Sort the intersections by distance.
         */
        Collections.sort(result);
        return result;
    }

    /**
     * Calculates a intersection with the face of a block defined by 3 points.
     * 
     * @param blockPos The position of the block to intersect with
     * @param v0 Point 1
     * @param v1 Point 2
     * @param v2 Point 3
     * @param origin Origin of the intersection ray
     * @param ray Direction of the intersection ray
     * @return Ray-face-intersection
     */
    private RayFaceIntersection rayFaceIntersection(Vector3f blockPos, Vector3f v0, Vector3f v1, Vector3f v2, Vector3f origin, Vector3f ray) {

        //Calculate the plane to intersect with.
        Vector3f a = Vector3f.sub(v1, v0, null);
        Vector3f b = Vector3f.sub(v2, v0, null);
        Vector3f norm = Vector3f.cross(a, b, null);


        float d = -(norm.x * v0.x + norm.y * v0.y + norm.z * v0.z);

        /**
         * Calculate the distance on the ray, where the intersection occurs.
         */
        float t = -(norm.x * origin.x + norm.y * origin.y + norm.z * origin.z + d) / (Vector3f.dot(ray, norm));

        if (t < 0) {
            return null;
        }

        /**
         * Calc. the point of intersection.
         */
        Vector3f intersectPoint = new Vector3f(ray.x * t, ray.y * t, ray.z * t);
        Vector3f.add(intersectPoint, origin, intersectPoint);

        if (intersectPoint.x >= v0.x && intersectPoint.x <= v2.x && intersectPoint.y >= v0.y && intersectPoint.y <= v2.y && intersectPoint.z >= v0.z && intersectPoint.z <= v2.z) {
            return new RayFaceIntersection(blockPos, v0, v1, v2, d, t, origin, ray, intersectPoint);
        }

        return null;
    }

    /**
     * Loads a specified chunk from the cache or queues a new chunk for
     * generation.
     *
     * NOTE: This method ALWAYS returns a valid chunk since new chunks
     * are generated if none of the present chunks fit.
     *
     * TODO: Chunks should be saved to and loaded from the hard disk!
     * 
     * @param x X-coordinate of the chunk
     * @param z Z-coordinate of the chunk
     * @return The chunk
     */
    private Chunk loadOrCreateChunk(int x, int z) {
        // Try to load the chunk directly
        Chunk c = getChunk(x, 0, z);

        // Okay, found a chunk
        if (c != null) {
            // Check if the chunk fits the position
            if (c.getPosition().x != x || c.getPosition().y != 0 || c.getPosition().z != z) {
                // If not, try to load the chunk from cache
                c = _chunkCache.get(Helper.getInstance().cantorize(x, z));
            }
        }

        // We got a chunk! Already! Great!
        if (c != null) {
            return c;
        } else {
            // Looks a like a new chunk has to be created from scratch
        }


        // Okay we have a full cache here. Alert!
        if (_chunkCache.size() >= 1024) {
            // Fetch all chunks within the cache
            ArrayList<Chunk> sortedChunks = null;
            sortedChunks = new ArrayList<Chunk>(_chunkCache.values());
            // Sort them according to their distance to the player
            Collections.sort(sortedChunks);

            Logger.getLogger(this.getClass().getName()).log(Level.INFO, "Cache full. Removing some chunks from the chunk cache...");

            // Delete as many elements as needed
            for (int i = 0; i < 256; i++) {
                int indexToDelete = sortedChunks.size() - i;

                if (indexToDelete >= 0 && indexToDelete < sortedChunks.size()) {
                    Chunk cc = sortedChunks.get(indexToDelete);
                    _chunkCache.remove(Helper.getInstance().cantorize((int) cc.getPosition().x, (int) cc.getPosition().z));
                    _chunkUpdateNormal.remove(cc);
                }
            }

            Logger.getLogger(this.getClass().getName()).log(Level.INFO, "Finished removing chunks from chunk cache.");
        }

        ArrayList<Generator> gs = new ArrayList<Generator>();
        gs.add(_generatorTerrain);
        gs.add(_generatorForest);

        // Generate a new chunk, cache it and return it
        c = new Chunk(this, new Vector3f(x, 0, z), gs);
        _chunkCache.put(Helper.getInstance().cantorize(x, z), c);

        return c;
    }

    /**
     * Marks the chunks stored within the chunk cache as dirty. If a chunk is dirty,
     * the vertex arrays are recreated the next time the chunk is queued for updating.
     *
     * @param markLightDirty If true the light will be recomputated
     */
    private void markCachedChunksDirty(boolean markLightDirty) {
        for (Chunk c : _chunkCache.values()) {
            c._dirty = true;
            c._lightDirty = markLightDirty;
        }
    }

    /**
     * Returns true if the given chunk is present in the cache.
     * 
     * @param c The chunk
     * @return True if the chunk is present in the chunk cache
     */
    private boolean isChunkCached(Chunk c) {
        return loadChunk((int) c.getPosition().x, (int) c.getPosition().z) != null;
    }

    /**
     * Tries to load a chunk from the cache. Returns null if no
     * chunk is found.
     * 
     * @param x X-coordinate
     * @param z Z-coordinate
     * @return The loaded chunk
     */
    private Chunk loadChunk(int x, int z) {
        Chunk c = _chunkCache.get(Helper.getInstance().cantorize(x, z));
        return c;
    }

    private void queueChunkForUpdate(Chunk c) {
        _chunkUpdateNormal.add(c);
    }

    /**
     * Displays some information about the world formatted as a string.
     * 
     * @return String with world information
     */
    @Override
    public String toString() {
        return String.format("world (cdl: %d, cn: %d, cache: %d, ud: %fs)", _chunkUpdateQueueDL.size(), _chunkUpdateNormal.size(), _chunkCache.size(), _statUpdateDuration / 1000d);
    }

    /**
     * Starts the updating thread.
     */
    public void startUpdateThread() {
        _updatingEnabled = true;
        _updateThread.start();
    }

    /**
     * Resumes the updating thread.
     */
    public void resumeUpdateThread() {
        _updatingEnabled = true;
        synchronized (_updateThread) {
            _updateThread.notify();
        }
    }

    /**
     * Safely suspends the updating thread.
     */
    public void suspendUpdateThread() {
        _updatingEnabled = false;
    }

    /**
     * Sets the time of the world.
     *
     * @param time The time to set
     */
    public void setTime(short time) {
        _time = (short) (time % 24);
    }
}
