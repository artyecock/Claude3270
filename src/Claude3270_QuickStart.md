***

# Claude3270 Emulator - Quick Start Guide

Follow these steps to build the emulator from source and enable the AI Assistant features.

### Prerequisites
*   **Java JDK 11 or higher** installed (`java -version` to check).
*   **Git** installed.

---

### 1. Get the Code
Clone the repository and navigate to the source directory.

```bash
git clone https://github.com/artyecock/Claude3270.git
cd Claude3270/src
```

### 2. Compile the Source
Create an output directory for the class files and compile the Java code.

```bash
mkdir ../bin
javac -d ../bin -sourcepath . com/tn3270/TN3270Emulator.java
```

### 3. Build the JAR
Package the compiled classes into an executable JAR file.

```bash
cd ../bin
jar cfe ../tn3270.jar com.tn3270.TN3270Emulator .
cd ..
```
*(You are now back in the project root folder).*

---

### 4. Get an AI API Key
To use the "Ask AI", "Attach Host File", and "Explain Code" features, you need an API key. **OpenAI** is the default provider.

1.  Go to **[platform.openai.com/api-keys](https://platform.openai.com/api-keys)**.
2.  Log in or Sign up.
3.  Click **"Create new secret key"**.
4.  Copy the key string (starts with `sk-...`).

*(Note: You can also use Google Gemini or local Ollama. See `.tn3270ai` comments for details).*

---

### 5. Configure the Emulator
Create a file named `.tn3270ai` (copy the .tn3270ai.sample from the src folder) in the project root folder (where the `.jar` is) and paste the following configuration. **Replace `YOUR_KEY_HERE` with the key you just copied.**

```properties
# .tn3270ai
# --- AI Provider Settings ---
ai.provider=openai
ai.apiKey=sk-YOUR_KEY_HERE
ai.models=gpt-4o, gpt-4o-mini

# --- RAG / Context Awareness ---
detect.order=cms_rexx, tso_rexx, jcl, asm
detect.cms_rexx.triggers=FILE: EXEC | TEXT:/* REXX */
detect.tso_rexx.triggers=FILE:.EXEC | TEXT:Address TSO
detect.jcl.triggers=TEXT:// JOB | FILE:.JCL

# --- Expert Personas ---
context.cms_rexx.prompt=You are a z/VM CMS REXX expert. Use 'Address CMS'.
context.tso_rexx.prompt=You are a z/OS TSO/E REXX expert. Handle TSO/ISPF services.
context.jcl.prompt=You are a z/OS JCL expert. Validate JOB cards and DD statements.
```

---

### 6. Run the Emulator
Launch the application.

```bash
java -jar tn3270.jar
```

### 7. Try the "Magic" Features
1.  **Connect** to your mainframe (TSO or CMS).
2.  Use the mouse to select an error message on the screen, and right click to open the AI Chat window.
    -or- 
    **Open the AI Chat** (`View` -> `Show Keyboard/AI` or `Edit` -> `Ask AI`), or select the "Ask AI" icon from the ribbon bar.
3.  **Attach a File:** Click the **Attach Host File** button in the AI window.
    *   Enter a dataset name (e.g., `USER.SOURCE(MYPROG)`).
    *   The emulator will auto-detect the OS, download the code, check for binary garbage, and feed it to the AI.
4.  **Save Code:** Ask the AI to write a REXX script. Click the **Save to Host** button inside the AI's response bubble to upload it directly to the mainframe.
