package com.winlator;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Close game?" dialog shown on Back (same look as Any Play and the other plugins). Built in code, no XML.
 * Yes runs [onYes]; No just closes the dialog.
 */
public final class CloseGameDialog {
    private CloseGameDialog() {}

    public static Dialog show(final Activity activity, final Runnable onYes) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        float dp = activity.getResources().getDisplayMetrics().density;

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) (24 * dp), (int) (24 * dp), (int) (24 * dp), (int) (20 * dp));
        card.setBackground(rounded(Color.parseColor("#1F2330"), 24 * dp));

        TextView title = new TextView(activity);
        title.setText("Close game?");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView message = new TextView(activity);
        message.setText("Do you want to close the game and go back? Unsaved progress may be lost.");
        message.setTextColor(Color.parseColor("#B9BDC9"));
        message.setTextSize(16);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = (int) (14 * dp);
        card.addView(message, mp);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = (int) (24 * dp);
        card.addView(buttons, bp);

        buttons.addView(button(activity, "No", Color.parseColor("#2E3342"), dp, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        }));
        View yes = button(activity, "Yes", Color.parseColor("#E5484D"), dp, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                onYes.run();
            }
        });
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yp.leftMargin = (int) (12 * dp);
        buttons.addView(yes, yp);

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            int width = (int) (Math.max(screenW, screenH) * 0.55f);
            window.setLayout(Math.min(width, (int) (Math.min(screenW, screenH) * 1.4f)), ViewGroup.LayoutParams.WRAP_CONTENT);
            // Keep immersive mode: the dialog must not bring the system bars back
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        if (window != null) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        return dialog;
    }

    private static TextView button(Activity activity, String text, int color, float dp, View.OnClickListener l) {
        TextView b = new TextView(activity);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth((int) (66 * dp));
        b.setPadding((int) (22 * dp), (int) (14 * dp), (int) (22 * dp), (int) (14 * dp));
        b.setBackground(rounded(color, 14 * dp));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
}
