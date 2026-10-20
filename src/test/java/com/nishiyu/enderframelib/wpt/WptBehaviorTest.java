package com.nishiyu.enderframelib.wpt;

import com.nishiyu.enderframelib.wpt.WptHarness.WptCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * WPT の挙動テストの基底クラス。
 *
 * <p>{@link WptCase} のパスを {@link WptHarness#test(String, org.junit.jupiter.api.function.Executable)}
 * に結び、サブテスト名と台帳（{@code wpt/known-divergences.txt}）を突き合わせられるようにする。
 * これが無いと、台帳に載っている既知差分が「ただの失敗」として落ち、逆に解消済みの行が残る。
 */
abstract class WptBehaviorTest {

    @BeforeEach
    void bindWptCase(TestInfo testInfo) {
        testInfo.getTestMethod()
                .map(method -> method.getAnnotation(WptCase.class))
                .ifPresent(wptCase -> WptHarness.bindCase(wptCase.path()));
    }

    @AfterEach
    void clearWptCase() {
        WptHarness.clearCase();
    }
}
