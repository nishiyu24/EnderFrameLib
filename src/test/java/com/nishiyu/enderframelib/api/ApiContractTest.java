package com.nishiyu.enderframelib.api;

import com.nishiyu.enderframelib.api.config.ConfigEntry;
import com.nishiyu.enderframelib.api.config.EnderConfig;
import com.nishiyu.enderframelib.api.dom.FormData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for public API contracts and type consolidation.
 */
public class ApiContractTest {

    @Test
    public void testStandardizedInterfaceContracts() {
        // Verify standardized public interfaces exist and are valid interfaces
        assertTrue(WebActionReceiver.class.isInterface(), "WebActionReceiver must be an interface");
        assertTrue(WebScreenBlockEntity.class.isInterface(), "WebScreenBlockEntity must be an interface");
        assertTrue(WebSessionProvider.class.isInterface(), "WebSessionProvider must be an interface");
    }

    @Test
    public void testEnderFrameApiProviderRegistrationSignatures() throws NoSuchMethodException {
        // Verify that EnderFrameAPI exposes clean WebSessionProvider registration methods
        Method register = EnderFrameAPI.class.getMethod("registerProvider", WebSessionProvider.class);
        assertNotNull(register);

        Method unregister = EnderFrameAPI.class.getMethod("unregisterProvider", WebSessionProvider.class);
        assertNotNull(unregister);
    }

    @Test
    public void testFormDataNestedEntryConsolidation() {
        FormData formData = new FormData();
        assertTrue(formData.isEmpty());
        assertEquals(0, formData.size());

        // Append entries using consolidated nested FormData.Entry
        formData.append("username", "steve");
        formData.append("role", "admin");
        formData.append("role", "builder");
        formData.append("upload", "avatar.png", "avatar.png");

        assertFalse(formData.isEmpty());
        assertEquals(4, formData.size());
        assertTrue(formData.has("username"));
        assertEquals("steve", formData.get("username"));

        List<String> roles = formData.getAll("role");
        assertEquals(2, roles.size());
        assertEquals(List.of("admin", "builder"), roles);

        List<FormData.Entry> entries = formData.getEntries();
        assertEquals(4, entries.size());
        assertEquals(new FormData.Entry("username", "steve", ""), entries.get(0));
        assertEquals(new FormData.Entry("upload", "avatar.png", "avatar.png"), entries.get(3));

        // Test delete and set
        formData.delete("role");
        assertFalse(formData.has("role"));
        assertEquals(2, formData.size());

        formData.set("username", "alex");
        assertEquals("alex", formData.get("username"));
        assertEquals(2, formData.size());

        // Test toString serialization
        String encoded = formData.toString();
        assertTrue(encoded.contains("username=alex"));
        assertTrue(encoded.contains("upload=avatar.png"));
    }

    @Test
    public void testConfigAnnotationsPresence() throws NoSuchFieldException {
        @EnderConfig(fileName = "sample.json", title = "Sample Config")
        class SampleConfigPojo {
            @ConfigEntry(category = "Display", min = 10, max = 100, step = 5, slider = true)
            private int fov = 70;
        }

        EnderConfig meta = SampleConfigPojo.class.getAnnotation(EnderConfig.class);
        assertNotNull(meta);
        assertEquals("sample.json", meta.fileName());
        assertEquals("Sample Config", meta.title());
        assertEquals("enderframelib:dark.css", meta.theme());

        ConfigEntry entry = SampleConfigPojo.class.getDeclaredField("fov").getAnnotation(ConfigEntry.class);
        assertNotNull(entry);
        assertEquals("Display", entry.category());
        assertEquals(10.0, entry.min());
        assertEquals(100.0, entry.max());
        assertEquals(5.0, entry.step());
        assertTrue(entry.slider());
        assertFalse(entry.colorPicker());
        assertFalse(entry.requiresRestart());
    }
}