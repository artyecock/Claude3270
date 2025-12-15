***

# TN3270 Emulator: AI Assistant Setup Guide

This guide covers how to enable the AI features in the TN3270 Emulator. You can choose to use public cloud providers (like OpenAI) or build your own private, local AI server using affordable hardware.

## Table of Contents
1.  [The `ai.conf` Configuration File](#1-the-aiconf-configuration-file)
2.  [Using Public Cloud LLMs](#2-using-public-cloud-llms)
3.  [Building a Private Local AI Server](#3-building-a-private-local-ai-server)
    *   [Hardware Recommendations](#hardware-recommendations)
    *   [Installing with Podman](#installing-with-podman)
    *   [Training with PDFs (AnythingLLM)](#training-with-pdfs)
4.  [Connecting the Emulator to Local AI](#4-connecting-the-emulator-to-local-ai)

---

## 1. The `ai.conf` Configuration File

To enable AI features, you must create a file named `ai.conf` in the same directory as the `TN3270.jar` file (or in your user home directory).

### File Format
The file uses a standard `key=value` format. Lines starting with `#` are comments.

**Template:**
```properties
# AI Provider: openai, anthropic, or local
ai.provider=openai

# Your API Key (Keep this secret!)
ai.api.key=sk-proj-12345...

# Model Name (Optional, defaults apply if omitted)
# Examples: gpt-4o, claude-3-5-sonnet, llama3.2
ai.model=gpt-4o

# Base URL (Only required for Local LLM / Custom endpoints)
# ai.api.base=http://localhost:3001/api/v1
```

---

## 2. Using Public Cloud LLMs

If you do not have the hardware to run a local model, using a public provider is the easiest way to start.

### OpenAI (ChatGPT)
1.  Go to [platform.openai.com](https://platform.openai.com/).
2.  Sign up and add a payment method (pre-paid credits).
3.  Navigate to **API Keys** and click **Create new secret key**.
4.  **Config:**
    *   `ai.provider=openai`
    *   `ai.api.key=YOUR_KEY_HERE`

### Anthropic (Claude)
1.  Go to [console.anthropic.com](https://console.anthropic.com/).
2.  Sign up and add credits.
3.  Click **Get API Keys**.
4.  **Config:**
    *   `ai.provider=anthropic`
    *   `ai.api.key=YOUR_KEY_HERE`

### Google (Gemini)
1.  Go to [aistudio.google.com](https://aistudio.google.com/).
2.  Click **Get API Key**.
3.  **Config:**
    *   `ai.provider=google`
    *   `ai.api.key=YOUR_KEY_HERE`

---

## 3. Building a Private Local AI Server

This section guides you through creating a "Private Cloud" that runs entirely on your own network. No data leaves your facility. We will use **Ollama** (the AI engine) and **AnythingLLM** (to manage documents/PDFs).

### Hardware Recommendations

Running AI requires RAM. The CPU speed determines how fast text is generated, but RAM determines if the model loads at all.

| Hardware | RAM | Recommendation |
| :--- | :--- | :--- |
| **Raspberry Pi 5** | 8GB | **Good Entry Level.** Capable of running 1B and 3B parameter models (Llama 3.2). Do not buy the 4GB version for AI. |
| **Orange Pi 5 Plus** | 16GB | **Best Value.** The 16GB RAM allows for smarter models (7B or 8B parameters). Significantly faster than the RPi 5. |
| **Mac Mini (M1/M2/M4)** | 16GB+ | **Performance King.** If you have the budget, Apple Silicon is the gold standard for local AI. |

**Storage Note:** Use a fast NVMe SSD (via USB adapter or HAT). SD Cards are too slow for loading AI models.

### Installing with Podman (Linux)

We recommend **Podman** over Docker for security. Podman runs "daemonless" and rootless. We will use "Storage Fencing" (Volumes) to ensure AI data persists but remains isolated from your host OS.

#### Step 1: Install Podman
*   **Raspberry Pi OS:** `sudo apt update && sudo apt install podman`
*   **Fedora/RedHat:** Pre-installed or `sudo dnf install podman`

#### Step 2: Create Fenced Volumes
Create isolated storage containers so your AI models and PDF database are safe.
```bash
podman volume create ollama_data
podman volume create anythingllm_data
```

#### Step 3: Run Ollama (The Backend)
This container runs the actual LLM.
```bash
podman run -d \
  --name ollama \
  --restart always \
  -v ollama_data:/root/.ollama:Z \
  -p 11434:11434 \
  docker.io/ollama/ollama:latest
```
*Note: The `:Z` flag tells SELinux to fence this content specifically for this container.*

#### Step 4: Run AnythingLLM (The Knowledge Base)
This provides the UI to upload PDFs and the API bridge for the Emulator.
```bash
podman run -d \
  --name anythingllm \
  --restart always \
  -p 3001:3001 \
  --cap-add SYS_ADMIN \
  -v anythingllm_data:/app/server/storage:Z \
  --env STORAGE_DIR="/app/server/storage" \
  docker.io/mintplexlabs/anythingllm:master
```

### Setup & Training with PDFs

1.  Open your browser and go to `http://<IP-ADDRESS-OF-PI>:3001`.
2.  **Setup Wizard:**
    *   **LLM Provider:** Select **Ollama**. Enter URL: `http://<IP-ADDRESS-OF-PI>:11434`.
    *   **Vector Database:** Use the built-in (LanceDB).
3.  **Workspace:** Create a workspace named "Mainframe".
4.  **Upload:** Click the "Upload" button and drag in your **JCL Manuals**, **COBOL Guides**, or **System Logs** (PDF, TXT, MD).
5.  **Embed:** Click "Move to Workspace" and then **"Save and Embed"**. This "trains" the system on your documents.
6.  **Generate API Key:**
    *   Go to **Settings (wrench icon) > Developer API**.
    *   Click **Create New API Key**.
    *   Copy this key.

---

## 4. Connecting the Emulator to Local AI

Now, connect your TN3270 Emulator to your private server.

1.  Edit your `ai.conf` file.
2.  Set the provider to `local` (or `openai` compatible).
3.  Point the Base URL to your AnythingLLM container.

```properties
# Use the Generic/OpenAI-Compatible provider
ai.provider=local

# The IP address of your Raspberry Pi / Orange Pi
# Port 3001 is AnythingLLM. Port 11434 is raw Ollama.
# To use your PDFs, you MUST use port 3001.
ai.api.base=http://192.168.1.50:3001/api/v1

# The API Key you generated in AnythingLLM settings
ai.api.key=A1B2-C3D4-E5F6-G7H8...

# The Specific Workspace slug (if required by custom logic) or generic model
ai.model=mainframe-workspace
```

### Sizing & Tuning Recommendations

If running on **Raspberry Pi 5 (8GB)**:
*   **Recommended Model:** `Llama 3.2 3B` (Quantized).
*   **Performance:** Expect 4-6 words per second.
*   **Setup:** In AnythingLLM, select "Llama 3.2 3B" as your model. Do not try to run Llama 3 8B or 70B; it will crash the Pi.

If running on **Orange Pi 5 (16GB)**:
*   **Recommended Model:** `Llama 3.1 8B` or `Qwen 2.5 7B`.
*   **Performance:** Expect 3-5 words per second (CPU mode).
*   **Setup:** You have enough RAM for "smarter" models that understand complex JCL better.

**Pro-Tip:** If the AI is responding too slowly, switch to a "Quantized" model type `q4_k_m` (4-bit quantization). It uses half the RAM of standard models with 95% of the intelligence.


When using **Podman**, you should apply "Hard Caps" to the container to ensure the AI doesn't starve the rest of the system.

### 1. Podman Memory Capping (The "Hard Fence")

When you launch your Ollama container, you should use the `--memory` and `--memory-reservation` flags. This forces the container to stay within a specific "lane."

**For a Raspberry Pi 5 (8GB RAM):**
Set the cap to 6GB. This leaves 2GB for the OS and AnythingLLM.
```bash
podman run -d \
  --name ollama \
  --memory 6g \
  --memory-reservation 4g \
  -p 11434:11434 \
  -v ollama_data:/root/.ollama:Z \
  docker.io/ollama/ollama:latest
```

**For an Orange Pi 5 (16GB RAM):**
Set the cap to 12GB.
```bash
--memory 12g --memory-reservation 8g
```

---

### 2. Ollama Environment Tuning (The "Soft Fence")

Ollama has internal settings that control how long it keeps models in memory and how it handles concurrent requests. You can pass these as environment variables in your Podman command.

#### `OLLAMA_NUM_PARALLEL`
By default, newer versions of Ollama try to handle multiple requests at once. This doubles or triples memory usage. **On an SBC, you must set this to 1.**
```bash
-e OLLAMA_NUM_PARALLEL=1
```

#### `OLLAMA_KEEP_ALIVE`
By default, Ollama keeps a model in RAM for 5 minutes after you use it. On a low-memory device, you might want it to clear RAM immediately so other processes (like AnythingLLM's document indexing) can use it.
*   `5m` (Default)
*   `0` (Unload immediately after every response)
```bash
-e OLLAMA_KEEP_ALIVE=0
```

---

### 3. Model Sizing (The "Knowledge Fence")

The most effective way to "cap" memory is to choose the correct **Quantization**. Models are like sponges; quantization determines how much they are "compressed."

*   **Standard (FP16):** Uses 2 bytes per parameter. (7B model = 14GB RAM). **Too big for Pi.**
*   **4-bit (q4_k_m):** Uses ~0.5 bytes per parameter. (7B model = 4.5GB RAM). **Perfect for SBCs.**

**Recommendation:** Always pull the `q4_k_m` or `q4_0` versions of models.
```bash
# Inside the container or via terminal
podman exec -it ollama ollama run llama3.2:3b-instruct-q4_K_M
```

---

### 4. Swap Space (The "Emergency Valve")

Single Board Computers usually have very small swap files (100MB). If the AI hits a peak, it will crash. You should increase your swap file to **4GB** on your NVMe drive. This is slower than RAM, but it prevents the "Damaged" or "Crashed" state during complex JCL analysis.

**On Raspberry Pi OS / Ubuntu:**
```bash
sudo dphys-swapfile swapoff
# Edit /etc/dphys-swapfile and set CONF_SWAPSIZE=4096
sudo dphys-swapfile setup
sudo dphys-swapfile swapon
```

### Summary Sizing Table for `ai.conf` users:

| Hardware | Target Model | Podman Mem Cap | Recommended Quant |
| :--- | :--- | :--- | :--- |
| **RPi 5 (8GB)** | Llama 3.2 (3B) | `4g` | `q4_k_m` |
| **Orange Pi (16GB)** | Llama 3.1 (8B) | `10g` | `q4_k_m` |
| **Orange Pi (16GB)** | Mistral (7B) | `9g` | `q4_k_m` |

**Final Tuning Tip:** If you notice the emulator's AI response is "stuttering," it's usually because the SBC is swapping. Reduce the `--memory` cap in Podman by 512MB to give the Linux Kernel more "breathing room" for disk caching.
