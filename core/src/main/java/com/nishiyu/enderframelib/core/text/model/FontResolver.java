package com.nishiyu.enderframelib.core.text.model;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

/**
 * {@code font-family} から書体を引く。
 */
public interface FontResolver {

    /** {@code null} や未知のファミリ名には既定の書体を返す（例外にしない）。 */
    FontFace resolve(String fontFamily);

    /** ヘッドレステスト用。常に {@link FontFace#FALLBACK}。 */
    FontResolver FALLBACK = fontFamily -> FontFace.FALLBACK;
}
