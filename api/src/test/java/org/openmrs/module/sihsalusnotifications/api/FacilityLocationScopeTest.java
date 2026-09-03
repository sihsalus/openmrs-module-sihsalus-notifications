package org.openmrs.module.sihsalusnotifications.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.openmrs.Location;
import org.openmrs.LocationTag;

public class FacilityLocationScopeTest {

    @Test
    public void resolvesTheNearestFacilityAncestor() {
        Location outerFacility = facility("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        Location innerFacility = facility("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        innerFacility.setParentLocation(outerFacility);
        Location service = location("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        service.setParentLocation(innerFacility);

        assertEquals(innerFacility.getUuid(), FacilityLocationScope.facilityUuid(service));
    }

    @Test
    public void failsClosedForUntaggedAndCyclicHierarchies() {
        Location first = location("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        Location second = location("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        first.setParentLocation(second);
        second.setParentLocation(first);

        assertNull(FacilityLocationScope.facilityUuid(first));
        assertNull(FacilityLocationScope.facilityUuid(null));
    }

    private Location facility(String uuid) {
        Location location = location(uuid);
        location.addTag(new LocationTag(FacilityLocationScope.FACILITY_LOCATION_TAG, "Synthetic facility tag"));
        return location;
    }

    private Location location(String uuid) {
        Location location = new Location();
        location.setUuid(uuid);
        return location;
    }
}
