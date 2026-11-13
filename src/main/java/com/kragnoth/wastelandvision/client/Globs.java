package com.kragnoth.wastelandvision.client;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;

/**
 * Texture-id globs: "*" matches anything (including "/"), everything else is literal.
 * A glob without a namespace ("block/*_log") means any namespace. Matched against "ns:path".
 */
public final class Globs {
    private Globs() {}

    public static Pattern compile(String glob) {
        String g = glob.trim();
        if (!g.contains(":")) g = "*:" + g;
        return Pattern.compile(toRegex(g));
    }

    private static String toRegex(String g) {
        StringBuilder re = new StringBuilder();
        StringBuilder lit = new StringBuilder();
        for (char c : g.toCharArray()) {
            if (c == '*') {
                if (lit.length() > 0) { re.append(Pattern.quote(lit.toString())); lit.setLength(0); }
                re.append(".*");
            } else {
                lit.append(c);
            }
        }
        if (lit.length() > 0) re.append(Pattern.quote(lit.toString()));
        return re.toString();
    }

    public static List<Pattern> compileAll(List<String> globs) {
        List<Pattern> out = new ArrayList<>();
        for (String g : globs) out.add(compile(g));
        return out;
    }

    public static boolean any(List<Pattern> patterns, ResourceLocation id) {
        String s = id.toString();
        for (Pattern p : patterns) if (p.matcher(s).matches()) return true;
        return false;
    }
}
