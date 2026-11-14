package com.featurestic.loader.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * High-performance 120 FPS Falling Leaves & Floating Petals Particle View.
 * Renders smooth floating and rotating monochrome leaves drifting across the background.
 */
public class FallingLeavesView extends View {

    private static final int LEAF_COUNT = 24;
    private final List<LeafParticle> mLeaves = new ArrayList<>();
    private final Paint mLeafPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mVeinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mLeafPath = new Path();
    private final Path mVeinPath = new Path();
    private final Random mRandom = new Random();

    private boolean mIsInitialized = false;

    public FallingLeavesView(Context context) {
        super(context);
        init();
    }

    public FallingLeavesView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FallingLeavesView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mLeafPaint.setStyle(Paint.Style.FILL);
        mVeinPaint.setStyle(Paint.Style.STROKE);
        mVeinPaint.setStrokeWidth(1.2f);
        mVeinPaint.setColor(Color.argb(70, 20, 25, 35));

        // Pre-build a realistic leaf silhouette geometry centered at (0, 0) normalized to [-1, 1]
        mLeafPath.reset();
        mLeafPath.moveTo(0, -1.0f);
        // Right curve
        mLeafPath.cubicTo(0.65f, -0.6f, 0.75f, 0.35f, 0, 1.0f);
        // Left curve
        mLeafPath.cubicTo(-0.75f, 0.35f, -0.65f, -0.6f, 0, -1.0f);
        mLeafPath.close();

        // Leaf center vein line
        mVeinPath.reset();
        mVeinPath.moveTo(0, -0.9f);
        mVeinPath.lineTo(0, 0.9f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && !mIsInitialized) {
            initParticles(w, h);
            mIsInitialized = true;
        }
    }

    private void initParticles(int width, int height) {
        mLeaves.clear();
        for (int i = 0; i < LEAF_COUNT; i++) {
            LeafParticle leaf = new LeafParticle();
            resetLeaf(leaf, width, height, true);
            mLeaves.add(leaf);
        }
    }

    private void resetLeaf(LeafParticle leaf, int width, int height, boolean scatterFullHeight) {
        leaf.x = mRandom.nextFloat() * width;
        leaf.y = scatterFullHeight ? (mRandom.nextFloat() * height) : (-20f - mRandom.nextFloat() * 80f);
        leaf.size = 14f + mRandom.nextFloat() * 18f; // 14dp - 32dp equivalent
        leaf.speedY = 1.2f + mRandom.nextFloat() * 2.2f;
        leaf.swayAmp = 1.0f + mRandom.nextFloat() * 2.0f;
        leaf.swayFreq = 0.015f + mRandom.nextFloat() * 0.025f;
        leaf.swayPhase = mRandom.nextFloat() * (float) Math.PI * 2f;
        leaf.rotation = mRandom.nextFloat() * 360f;
        leaf.rotSpeed = -1.2f + mRandom.nextFloat() * 2.4f;
        // Natural semi-transparent graphite/charcoal tones matching background foliage
        int alpha = 45 + mRandom.nextInt(95);
        int grayTone = 35 + mRandom.nextInt(45);
        leaf.color = Color.argb(alpha, grayTone, grayTone + 5, grayTone + 15);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        for (int i = 0; i < mLeaves.size(); i++) {
            LeafParticle leaf = mLeaves.get(i);

            // Update physics
            leaf.y += leaf.speedY;
            leaf.swayPhase += leaf.swayFreq;
            leaf.x += (float) Math.sin(leaf.swayPhase) * leaf.swayAmp;
            leaf.rotation += leaf.rotSpeed;

            // Reset if out of bounds
            if (leaf.y > h + 50f || leaf.x < -60f || leaf.x > w + 60f) {
                resetLeaf(leaf, w, h, false);
            }

            // Draw leaf
            canvas.save();
            canvas.translate(leaf.x, leaf.y);
            canvas.rotate(leaf.rotation);
            canvas.scale(leaf.size, leaf.size * 1.35f);

            mLeafPaint.setColor(leaf.color);
            canvas.drawPath(mLeafPath, mLeafPaint);
            canvas.drawPath(mVeinPath, mVeinPaint);

            canvas.restore();
        }

        // 120 FPS frame request
        postInvalidateOnAnimation();
    }

    private static class LeafParticle {
        float x;
        float y;
        float size;
        float speedY;
        float swayAmp;
        float swayFreq;
        float swayPhase;
        float rotation;
        float rotSpeed;
        int color;
    }
}
