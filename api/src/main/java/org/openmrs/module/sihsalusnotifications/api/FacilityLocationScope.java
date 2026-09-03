package org.openmrs.module.sihsalusnotifications.api;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.openmrs.Location;

/** Resolves a service location to its nearest owning health facility. */
public final class FacilityLocationScope {

    public static final String FACILITY_LOCATION_TAG = "Facility Location";

    private FacilityLocationScope() {
    }

    public static String facilityUuid(Location location) {
        Set<Location> visited = Collections.newSetFromMap(new IdentityHashMap<Location, Boolean>());
        Location current = location;
        while (current != null && visited.add(current)) {
            String uuid = current.getUuid();
            if (Boolean.TRUE.equals(current.hasTag(FACILITY_LOCATION_TAG))
                    && uuid != null && !uuid.trim().isEmpty()) {
                return uuid;
            }
            current = current.getParentLocation();
        }
        return null;
    }
}
