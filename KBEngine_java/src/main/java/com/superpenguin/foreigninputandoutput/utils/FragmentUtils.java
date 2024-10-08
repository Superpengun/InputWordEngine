/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superpenguin.foreigninputandoutput.utils;

import com.superpenguin.foreigninputandoutput.dictionarypack.DictionarySettingsFragment;
import com.superpenguin.foreigninputandoutput.method.settings.CustomInputStyleSettingsFragment;
import com.superpenguin.foreigninputandoutput.method.settings.PreferencesSettingsFragment;
import com.superpenguin.foreigninputandoutput.method.userdictionary.UserDictionaryAddWordFragment;
import com.superpenguin.foreigninputandoutput.method.userdictionary.UserDictionaryList;
import com.superpenguin.foreigninputandoutput.method.userdictionary.UserDictionaryLocalePicker;
import com.superpenguin.foreigninputandoutput.method.userdictionary.UserDictionarySettings;

import java.util.HashSet;

public class FragmentUtils {
    private static final HashSet<String> sLatinImeFragments = new HashSet<>();
    static {
        sLatinImeFragments.add(DictionarySettingsFragment.class.getName());
        sLatinImeFragments.add(PreferencesSettingsFragment.class.getName());
        sLatinImeFragments.add(CustomInputStyleSettingsFragment.class.getName());
        sLatinImeFragments.add(UserDictionaryAddWordFragment.class.getName());
        sLatinImeFragments.add(UserDictionaryList.class.getName());
        sLatinImeFragments.add(UserDictionaryLocalePicker.class.getName());
        sLatinImeFragments.add(UserDictionarySettings.class.getName());
    }

    public static boolean isValidFragment(String fragmentName) {
        return sLatinImeFragments.contains(fragmentName);
    }
}
