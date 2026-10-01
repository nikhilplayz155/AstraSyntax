package io.astra.runtime.region;

/**
 * A named cuboid region declared with {@code region <name>: ...}.
 *
 * <p>The maths lives here, free of Bukkit, so containment can be unit tested: a region is
 * a world name plus two opposite corners, normalised at construction so a script may list
 * the corners in any order.</p>
 */
public record RegionDefinition(String name, String world, double minX, double minY, double minZ, double maxX,
                               double maxY, double maxZ) {

    public static RegionDefinition of(String name, String world, double x1, double y1, double z1, double x2,
                                      double y2, double z2) {
        return new RegionDefinition(name, world,
            Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
            Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** True when the point is inside the box and the world matches. */
    public boolean contains(String worldName, double x, double y, double z) {
        if (world != null && worldName != null && !world.equalsIgnoreCase(worldName)) return false;
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** True when two regions overlap. */
    public boolean overlaps(RegionDefinition other) {
        if (other == null) return false;
        if (world != null && other.world != null && !world.equalsIgnoreCase(other.world)) return false;
        return minX <= other.maxX && maxX >= other.minX
            && minY <= other.maxY && maxY >= other.minY
            && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public double volume() {
        return (maxX - minX) * (maxY - minY) * (maxZ - minZ);
    }

    /** One line for {@code /astra explain}. */
    public String describe() {
        return "region " + name + " in " + (world == null ? "any world" : world)
            + " (" + minX + "," + minY + "," + minZ + ") -> (" + maxX + "," + maxY + "," + maxZ + ")";
    }
}
