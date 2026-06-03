package com.qiniu.back.module.chat.rag;

/**
 * RAG 检索命中结果。
 */
public class RagHit implements Comparable<RagHit> {

    private String id;
    private String source;
    private String section;
    private String text;
    private int charCount;
    private float score;

    public RagHit() {}

    public RagHit(String id, String source, String section, String text, int charCount, float score) {
        this.id = id;
        this.source = source;
        this.section = section;
        this.text = text;
        this.charCount = charCount;
        this.score = score;
    }

    @Override
    public int compareTo(RagHit o) {
        return Float.compare(o.score, this.score);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public int getCharCount() { return charCount; }
    public void setCharCount(int charCount) { this.charCount = charCount; }

    public float getScore() { return score; }
    public void setScore(float score) { this.score = score; }
}
