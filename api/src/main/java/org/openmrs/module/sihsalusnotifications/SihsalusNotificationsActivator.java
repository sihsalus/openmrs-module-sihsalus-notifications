package org.openmrs.module.sihsalusnotifications;

import org.openmrs.module.BaseModuleActivator;

/**
 * OpenMRS lifecycle entry point for the realtime notifications module.
 *
 * <p>The transports are registered through the module web descriptor. A concrete activator is
 * still required because OpenMRS resolves the configured lifecycle class before it installs the
 * module's servlets and filters.</p>
 */
public final class SihsalusNotificationsActivator extends BaseModuleActivator {
}
