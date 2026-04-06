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

import android.os.Bundle;

/**
 * Callback interface for streaming AI agent execution updates.
 * @hide
 */
oneway interface IAgentServiceCallback {
    void onThinking(String thinking);
    void onText(String text);
    void onToolResult(String toolName, in Bundle result);
    void onComplete();
    void onError(String error);
}
