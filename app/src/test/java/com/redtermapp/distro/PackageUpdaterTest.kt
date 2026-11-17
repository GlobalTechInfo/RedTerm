package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PackageUpdaterTest {

    @Test
    fun amazonLinuxIsDnf() {
        val plan = PackageUpdater.planFor("amazonlinux", File("/nonexistent"))
        assertEquals("dnf", plan.packageManager)
        assertEquals("dnf -y upgrade", plan.updateCommand)
    }

    @Test
    fun nixosIsNamedNotUnknown() {
        val plan = PackageUpdater.planFor("nixos", File("/nonexistent"))
        assertEquals("nix", plan.packageManager)
        // The empty command is deliberate: the activity shows the dedicated
        // no-updates explanation instead of running anything.
        assertEquals("", plan.updateCommand)
    }

    @Test
    fun nixPlanIsANoOp() {
        val plan = PackageUpdater.planForManager("nix")
        assertEquals("nix", plan.packageManager)
        assertEquals("", plan.updateCommand)
    }
}
