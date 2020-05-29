package xyz.zedler.patrick.grocy.fragment.bottomSheetDialog;

/*
    This file is part of Grocy Android.

    Grocy Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Grocy Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Grocy Android.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2020 by Patrick Zedler & Dominic Zedler
*/

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import xyz.zedler.patrick.grocy.MainActivity;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.fragment.MasterProductEditSimpleFragment;
import xyz.zedler.patrick.grocy.util.Constants;

public class TextEditBottomSheetDialogFragment extends BottomSheetDialogFragment {

    private final static String TAG = "TextEditBottomSheet";

    private MainActivity activity;

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        return new BottomSheetDialog(requireContext(), R.style.Theme_Grocy_BottomSheetDialog);
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState
    ) {
        View view = inflater.inflate(
                R.layout.fragment_bottomsheet_text_edit, container, false
        );

        activity = (MainActivity) getActivity();
        assert activity != null;

        if(getArguments() == null
                || getArguments().getString(Constants.ARGUMENT.TITLE) == null
                || getArguments().getString(Constants.ARGUMENT.HINT) == null
        ) {
            dismissWithMessage(activity.getString(R.string.msg_error));
            return view;
        }

        setCancelable(false);

        TextView textView = view.findViewById(R.id.text_text_edit_title);
        textView.setText(getArguments().getString(Constants.ARGUMENT.TITLE));

        TextInputLayout textInputLayout = view.findViewById(R.id.text_input_text_edit_text);
        textInputLayout.setHint(getArguments().getString(Constants.ARGUMENT.HINT));
        EditText editText = textInputLayout.getEditText();
        assert editText != null;
        editText.setText(getArguments().getString(Constants.ARGUMENT.TEXT));

        view.findViewById(R.id.button_text_edit_ok).setOnClickListener(v -> {
            Fragment current = activity.getCurrentFragment();
            if(current.getClass() == MasterProductEditSimpleFragment.class) {
                ((MasterProductEditSimpleFragment) current).editDescription(
                        editText.getText().toString()
                );
            }
            dismiss();
        });

        view.findViewById(R.id.button_text_edit_cancel).setOnClickListener(v -> dismiss());

        view.findViewById(R.id.button_text_edit_clear).setOnClickListener(
                v -> editText.setText(null)
        );

        return view;
    }

    private void dismissWithMessage(String msg) {
        activity.showMessage(
                Snackbar.make(
                        activity.findViewById(R.id.linear_container_main),
                        msg,
                        Snackbar.LENGTH_SHORT
                )
        );
        dismiss();
    }

    @NonNull
    @Override
    public String toString() {
        return TAG;
    }
}