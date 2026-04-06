/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.app.ai;

import android.app.ai.IAgentServiceCallback;
import android.graphics.Bitmap;

/**
 * Binder interface for communicating with the platform AI agent service.
 * @hide
 */
interface IAgentService {
    void sendTask(String task, IAgentServiceCallback callback);
    boolean isEnabled();
    void setEnabled(boolean enabled);
    void setApiKey(String apiKey);
    String getApiKey();
    Bitmap takeScreenshot();
    boolean click(int x, int y);
    boolean swipe(int x1, int y1, int x2, int y2, int duration);
    boolean inputText(String text);
    boolean pressBack();
    boolean pressHome();
    boolean launchApp(String packageName);
}