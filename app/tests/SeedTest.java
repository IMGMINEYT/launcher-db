package com.imgmine.launcher67;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SeedTest {

    @Test
    public void browseBedrockOnly() {
        List<Addon> all = Seeds.browse(null);
        assertNotNull("browse(all) failed", all);
        assertTrue("no seeds", all.size() >= 10);
        for (Addon a : all) {
            assertEquals("seed", a.type);
            assertTrue("name missing", a.name.length() > 3);
            assertTrue("page url missing: " + a.name, a.page.startsWith("https://wisehosting.com/minecraft-seeds/"));
        }
        System.out.println("ALL BEDROCK seeds: " + all.size() + " first=" + all.get(0).name + " img=" + (all.get(0).banner != null));
    }

    @Test
    public void browseTagVillage() {
        List<Addon> v = Seeds.browse("village");
        assertNotNull("browse(village) failed", v);
        assertTrue("no village seeds", v.size() >= 5);
        System.out.println("VILLAGE seeds: " + v.size() + " first=" + v.get(0).name);
    }

    @Test
    public void detailExactSeed() {
        Addon d = new Addon();
        d.id = "sdvillage-on-a-deep-lake-surrounded-by-cherry-grove-trees";
        d.page = "https://wisehosting.com/minecraft-seeds/village-on-a-deep-lake-surrounded-by-cherry-grove-trees";
        assertTrue("fillDetail failed", Seeds.fillDetail(d));
        assertEquals("712262452098460", d.seed);
        System.out.println("SEED=" + d.seed + " COORDS=" + d.seedCoords + " DESC[" + d.desc.length() + "]=" + d.desc.substring(0, Math.min(120, d.desc.length())));
        assertTrue("no description", d.desc.length() > 40);
    }

    @Test
    public void detailPreservesNegativeAndLongSeeds() {
        // any Bedrock card from the live list; seed string must match the site's <code> exactly
        List<Addon> all = Seeds.browse(null);
        assertNotNull(all);
        Addon a = all.get(0);
        assertTrue(Seeds.fillDetail(a));
        assertTrue("seed empty", a.seed != null && a.seed.length() >= 4);
        assertTrue("seed not numeric-ish: " + a.seed, a.seed.matches("-?\\d+"));
        System.out.println("LIVE SEED=" + a.seed + " for " + a.name);
    }
}
