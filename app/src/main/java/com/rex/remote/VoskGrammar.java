package com.rex.remote;

/** Closed command vocabulary + [unk] escape, never a new command synonym. */
public final class VoskGrammar {
    private VoskGrammar() {}
    public static String chinese() {
        StringBuilder out=new StringBuilder("[");
        for(String phrase:CommandPhrases.ALL) {
            if(phrase.equals("Rex") || phrase.equals("turn around") ||
               phrase.equals("disable") || phrase.equals("active")) continue;
            append(out,phrase.replace("，", "").replace("、", ""));
        }
        // The English wake word may precede an approved Chinese action.
        append(out,"rex");
        append(out,"rex 醒了吗");
        append(out,"[unk]");
        return out.append(']').toString();
    }
    public static String english() {
        StringBuilder out=new StringBuilder("[");
        for(String phrase:new String[]{"rex","turn around","disable","active","[unk]"})
            append(out,phrase);
        return out.append(']').toString();
    }
    private static void append(StringBuilder out,String phrase) {
        if(out.length()>1)out.append(',');
        out.append('"').append(phrase.replace("\\","\\\\").replace("\"","\\\"")).append('"');
    }
}
