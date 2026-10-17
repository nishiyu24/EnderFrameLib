package com.nishiyu.enderframelib.core.css;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StyleSheet {
    public List<StyleRule> rules = new ArrayList<>();
    public Map<String, String> keyframes = new HashMap<>();
    public Map<String, String> variables = new HashMap<>();
}