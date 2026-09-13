package com.npucraft.farmguard.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LocaleIdsTest {

    @Test
    void normalizesChineseAndEnglishAliases() {
        assertEquals("zh_CN", LocaleIds.tryNormalize("zh_cn"));
        assertEquals("zh_CN", LocaleIds.tryNormalize("ZH_CN"));
        assertEquals("zh_CN", LocaleIds.tryNormalize("zh-CN"));
        assertEquals("zh_CN", LocaleIds.tryNormalize("zh"));
        assertEquals("en_US", LocaleIds.tryNormalize("en_us"));
        assertEquals("en_US", LocaleIds.tryNormalize("EN-US"));
        assertEquals("en_US", LocaleIds.tryNormalize("en"));
    }

    @Test
    void keepsFutureWellFormedLocales() {
        assertEquals("ja_JP", LocaleIds.tryNormalize("ja-jp"));
        assertEquals("zh_TW", LocaleIds.tryNormalize("zh_tw"));
        assertEquals("de_DE", LocaleIds.tryNormalize("de-DE"));
    }

    @Test
    void rejectsGarbage() {
        assertNull(LocaleIds.tryNormalize("abc"));
        assertNull(LocaleIds.tryNormalize(""));
        assertNull(LocaleIds.tryNormalize(null));
        assertEquals("zh_CN", LocaleIds.canonicalize("nope"));
    }
}
