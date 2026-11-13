package com.tn3270.debug;

import static com.tn3270.util.EBCDIC.EBCDIC_TO_ASCII;

import com.tn3270.model.ScreenModel;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Always-on, bounded recorder of 3270 data streams in BOTH directions.
 *
 * Purpose: when the host application complains, press the dump hotkey and get
 * one text file containing the last N records, each with
 *   - a full hex dump (EBCDIC sidebar),
 *   - an order-aware decode (command, WCC, SBA/SF/SFE/SA/MF/RA/EUA/IC/PT/GE
 *     with correct operand lengths, text runs run-length compressed),
 *   - a screen snapshot: for IN records the emulator's buffer AFTER the stream
 *     was applied; for OUT records the buffer as it stood when the reply was
 *     built.  NUL (shown as a middle dot) is distinguished from a real space.
 *
 * Deliberately independent of DataStreamDebugger / ScrollBackManager and of
 * debug mode: no replay, no screen mutation, nothing that can disturb the
 * live session.  All public methods are cheap and thread-safe.
 */
public class StreamRecorder {

	private enum Kind { IN, OUT, NOTE }

	private static final int MAX_STORED_BYTES = 16384;
	private static final int MAX_FIELDS_LISTED = 150;
	private static final char NUL_GLYPH = '\u00B7';   // middle dot
	private static final char ATTR_GLYPH = '\u00A4';  // currency sign = field attribute position

	private static final class Record {
		final long seq;
		final long wallMillis;
		final Kind kind;
		final byte[] bytes;
		final int fullLength;
		final boolean tn3270e;
		final int rows;
		final int cols;
		String screen;  // IN: state after processing, OUT: state when reply was built
		String text;    // NOTE text, or OUT label

		Record(long seq, Kind kind, byte[] data, boolean tn3270e, int rows, int cols) {
			this.seq = seq;
			this.wallMillis = System.currentTimeMillis();
			this.kind = kind;
			this.fullLength = data == null ? 0 : data.length;
			if (data == null) {
				this.bytes = new byte[0];
			} else if (data.length > MAX_STORED_BYTES) {
				this.bytes = java.util.Arrays.copyOf(data, MAX_STORED_BYTES);
			} else {
				this.bytes = data.clone();
			}
			this.tn3270e = tn3270e;
			this.rows = rows;
			this.cols = cols;
		}
	}

	private final int capacity;
	private final ArrayDeque<Record> ring = new ArrayDeque<>();
	private long seq = 0;
	private Record lastInbound;

	// Pre-send snapshot staged by sendAID() and consumed by the next matching recordOutbound().
	private String stagedScreen;
	private String stagedLabel;
	private int stagedAid = -1;
	private long stagedMillis;

	public StreamRecorder(int capacity) {
		this.capacity = Math.max(8, capacity);
	}

	// ======================================================================
	// Recording API
	// ======================================================================

	/** Record one complete, IAC-unescaped inbound record exactly as assembled at IAC EOR. */
	public synchronized void recordInbound(byte[] data, boolean tn3270e, int rows, int cols) {
		Record r = new Record(++seq, Kind.IN, data, tn3270e, rows, cols);
		lastInbound = r;
		add(r);
	}

	/** Call after the inbound record has been fully applied to the screen model. */
	public synchronized void attachPostState(ScreenModel sm) {
		Record r = lastInbound;
		if (r == null || r.screen != null || sm == null)
			return;
		if (!affectsScreen(r))
			return;
		r.screen = renderScreen(sm);
	}

	/** Call from sendAID() just before the reply is built, so the OUT record gets a pre-send snapshot. */
	public synchronized void stagePreSend(ScreenModel sm, int aid, boolean includeProtected) {
		if (sm == null)
			return;
		stagedScreen = renderScreen(sm);
		stagedAid = aid & 0xFF;
		stagedLabel = "reply built with includeProtected=" + includeProtected;
		stagedMillis = System.currentTimeMillis();
	}

	/** Record an outbound payload (AID + cursor + data, before IAC doubling / TN3270E header). */
	public synchronized void recordOutbound(byte[] data, boolean tn3270eWrapped, int rows, int cols) {
		Record r = new Record(++seq, Kind.OUT, data, tn3270eWrapped, rows, cols);
		if (stagedScreen != null && data != null && data.length > 0 && (data[0] & 0xFF) == stagedAid
				&& System.currentTimeMillis() - stagedMillis < 5000) {
			r.screen = stagedScreen;
			r.text = stagedLabel;
		}
		stagedScreen = null;
		add(r);
	}

	/** Free-form marker (connect, disconnect, mode changes...). */
	public synchronized void note(String text) {
		Record r = new Record(++seq, Kind.NOTE, null, false, 0, 0);
		r.text = text;
		add(r);
	}

	public synchronized void clear() {
		ring.clear();
		lastInbound = null;
		stagedScreen = null;
	}

	public synchronized int size() {
		return ring.size();
	}

	private void add(Record r) {
		ring.addLast(r);
		while (ring.size() > capacity)
			ring.removeFirst();
	}

	// ======================================================================
	// Report generation
	// ======================================================================

	/** Build the full text report.  {@code live} (optional) adds a final current-screen snapshot. */
	public synchronized String formatReport(ScreenModel live) {
		StringBuilder sb = new StringBuilder(64 * 1024);
		SimpleDateFormat full = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		SimpleDateFormat tm = new SimpleDateFormat("HH:mm:ss.SSS");

		sb.append("=== TN3270 STREAM RECORDER REPORT ===\n");
		sb.append("Generated : ").append(full.format(new Date())).append('\n');
		sb.append("Records   : ").append(ring.size()).append(" (capacity ").append(capacity).append(")\n");
		sb.append("Legend    : IN = host->emulator, OUT = emulator->host\n");
		sb.append("            '").append(NUL_GLYPH).append("' = NUL in buffer (distinct from a real space), '")
				.append(ATTR_GLYPH).append("' = field-attribute position\n");
		sb.append("            OUT payloads are shown before IAC doubling and before the 5-byte TN3270E header\n");
		sb.append("            (when the header applies it is always 00 00 00 00 00 in this build).\n");
		sb.append("            Hex sidebar is EBCDIC->ASCII.  Offsets in the decode match the hex dump.\n");
		sb.append("=".repeat(78)).append('\n');

		for (Record r : ring) {
			sb.append('\n');
			if (r.kind == Kind.NOTE) {
				sb.append(String.format("[#%d] %s  NOTE  %s%n", r.seq, tm.format(new Date(r.wallMillis)), r.text));
				continue;
			}
			sb.append(String.format("[#%d] %s  %-3s  %s  (%d bytes%s)%n", r.seq, tm.format(new Date(r.wallMillis)),
					r.kind, summarize(r), r.fullLength,
					r.fullLength > r.bytes.length ? ", TRUNCATED to " + r.bytes.length : ""));
			sb.append("-".repeat(78)).append('\n');
			hexDump(sb, r.bytes);
			sb.append("  decode:\n");
			if (r.kind == Kind.IN)
				decodeInbound(sb, r);
			else
				decodeOutbound(sb, r);
			if (r.screen != null) {
				sb.append(r.kind == Kind.IN ? "  screen AFTER applying this stream:\n"
						: "  screen when this reply was built (" + r.text + "):\n");
				indent(sb, r.screen, "    ");
			}
		}

		if (live != null) {
			sb.append('\n').append("=".repeat(78)).append('\n');
			sb.append("CURRENT SCREEN AT EXPORT TIME\n");
			indent(sb, renderScreen(live), "  ");
		}
		sb.append('\n').append("=".repeat(78)).append('\n');
		return sb.toString();
	}

	/** Write the report to {@code dir/tn3270-streams-yyyyMMdd-HHmmss.txt} (UTF-8) and return the file. */
	public File writeReport(File dir, ScreenModel live) throws IOException {
		if (!dir.exists() && !dir.mkdirs())
			throw new IOException("Cannot create directory " + dir);
		String name = "tn3270-streams-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt";
		File f = new File(dir, name);
		Files.write(f.toPath(), formatReport(live).getBytes(StandardCharsets.UTF_8));
		return f;
	}

	// ======================================================================
	// Summaries
	// ======================================================================

	private static String summarize(Record r) {
		byte[] d = r.bytes;
		if (d.length == 0)
			return "(empty)";
		if (r.kind == Kind.OUT) {
			int aid = d[0] & 0xFF;
			String s = "AID=" + aidName(aid);
			if (aid != 0x88 && d.length >= 3) {
				int cur = decodeAddr(d[1] & 0xFF, d[2] & 0xFF);
				s += " cursor=" + addrStr(cur, r.cols);
			}
			return s;
		}
		int off = commandOffset(r);
		if (off < 0)
			return "TN3270E " + tn3270eTypeName(d[0] & 0xFF);
		String pre = r.tn3270e ? "TN3270E 3270-DATA " : "raw ";
		return off < d.length ? pre + commandName(d[off] & 0xFF) : pre + "(header only)";
	}

	/**
	 * Offset of the 3270 command byte within an inbound record, or -1 when the record is a
	 * non-3270-DATA TN3270E frame (BIND, UNBIND, RESPONSE, ...).
	 */
	private static int commandOffset(Record r) {
		byte[] d = r.bytes;
		if (!r.tn3270e)
			return 0;
		if (d.length >= 1 && (d[0] & 0xFF) == 0x00)
			return Math.min(5, d.length);
		return -1;
	}

	private static boolean affectsScreen(Record r) {
		int off = commandOffset(r);
		if (off < 0 || off >= r.bytes.length)
			return false;
		switch (r.bytes[off] & 0xFF) {
		case 0x01: case 0xF1:
		case 0x05: case 0xF5:
		case 0x0D: case 0x7E:
		case 0x0F: case 0x6F:
		case 0x11: case 0xF3:
			return true;
		default:
			return false;
		}
	}

	// ======================================================================
	// Decoders
	// ======================================================================

	private static void decodeInbound(StringBuilder sb, Record r) {
		byte[] d = r.bytes;
		int off = commandOffset(r);
		if (off < 0) {
			sb.append("    TN3270E frame type=").append(tn3270eTypeName(d[0] & 0xFF))
					.append(" - not 3270 data; see hex dump\n");
			return;
		}
		if (r.tn3270e) {
			if (d.length >= 5) {
				sb.append(String.format("    TN3270E header: type=3270-DATA req=%02X rsp=%02X seq=%04X%n",
						d[1] & 0xFF, d[2] & 0xFF, ((d[3] & 0xFF) << 8) | (d[4] & 0xFF)));
			} else {
				sb.append("    TN3270E header: incomplete\n");
				return;
			}
		}
		if (off >= d.length) {
			sb.append("    (no command byte)\n");
			return;
		}
		int cmd = d[off] & 0xFF;
		int i = off;
		line(sb, i, "CMD", commandName(cmd) + String.format(" (%02X)", cmd));
		i++;
		switch (cmd) {
		case 0x01: case 0xF1:
		case 0x05: case 0xF5:
		case 0x0D: case 0x7E:
			if (i < d.length) {
				line(sb, i, "WCC", wccString(d[i] & 0xFF));
				i++;
			}
			decodeOrders(sb, d, i, r.cols);
			break;
		case 0x11: case 0xF3:
			decodeStructuredFields(sb, d, i);
			break;
		default:
			if (i < d.length)
				line(sb, i, "DATA", (d.length - i) + " trailing byte(s)");
			break;
		}
	}

	private static void decodeOutbound(StringBuilder sb, Record r) {
		byte[] d = r.bytes;
		if (d.length == 0) {
			sb.append("    (empty)\n");
			return;
		}
		int aid = d[0] & 0xFF;
		line(sb, 0, "AID", aidName(aid) + String.format(" (%02X)", aid));
		if (aid == 0x88) {
			line(sb, 1, "SF", "structured-field reply, " + (d.length - 1) + " byte(s); see hex dump");
			return;
		}
		if (d.length < 3) {
			sb.append("    (no cursor address)\n");
			return;
		}
		int cur = decodeAddr(d[1] & 0xFF, d[2] & 0xFF);
		line(sb, 1, "CURSOR", addrStr(cur, r.cols));
		decodeOrders(sb, d, 3, r.cols);
	}

	private static boolean isOrder(int b) {
		switch (b) {
		case 0x11: case 0x1D: case 0x29: case 0x28: case 0x2C:
		case 0x3C: case 0x12: case 0x13: case 0x05: case 0x08:
			return true;
		default:
			return false;
		}
	}

	private static void decodeOrders(StringBuilder sb, byte[] d, int i, int cols) {
		int n = d.length;
		while (i < n) {
			int b = d[i] & 0xFF;
			int start = i;
			switch (b) {
			case 0x11: // SBA
				if (n - i < 3)
					{ truncated(sb, start, "SBA"); return; }
				line(sb, start, "SBA", addrStr(decodeAddr(d[i + 1] & 0xFF, d[i + 2] & 0xFF), cols));
				i += 3;
				break;
			case 0x12: // EUA
				if (n - i < 3)
					{ truncated(sb, start, "EUA"); return; }
				line(sb, start, "EUA", "erase unprotected to "
						+ addrStr(decodeAddr(d[i + 1] & 0xFF, d[i + 2] & 0xFF), cols));
				i += 3;
				break;
			case 0x13:
				line(sb, start, "IC", "insert cursor");
				i++;
				break;
			case 0x05:
				line(sb, start, "PT", "program tab");
				i++;
				break;
			case 0x1D: // SF
				if (n - i < 2)
					{ truncated(sb, start, "SF"); return; }
				line(sb, start, "SF", String.format("attr=%02X %s", d[i + 1] & 0xFF, attrFlags(d[i + 1] & 0xFF)));
				i += 2;
				break;
			case 0x29: // SFE
			case 0x2C: { // MF
				String nm = b == 0x29 ? "SFE" : "MF";
				if (n - i < 2)
					{ truncated(sb, start, nm); return; }
				int count = d[i + 1] & 0xFF;
				int total = 2 + 2 * count;
				if (n - i < total)
					{ truncated(sb, start, nm); return; }
				StringBuilder pairs = new StringBuilder();
				for (int k = 0; k < count; k++) {
					if (k > 0)
						pairs.append("  ");
					pairs.append(pairString(d[i + 2 + 2 * k] & 0xFF, d[i + 3 + 2 * k] & 0xFF));
				}
				line(sb, start, nm, "count=" + count + "  " + pairs);
				i += total;
				break;
			}
			case 0x28: // SA
				if (n - i < 3)
					{ truncated(sb, start, "SA"); return; }
				line(sb, start, "SA", pairString(d[i + 1] & 0xFF, d[i + 2] & 0xFF));
				i += 3;
				break;
			case 0x3C: { // RA
				if (n - i < 4)
					{ truncated(sb, start, "RA"); return; }
				int to = decodeAddr(d[i + 1] & 0xFF, d[i + 2] & 0xFF);
				int ch = d[i + 3] & 0xFF;
				int len = 4;
				String chs;
				if (ch == 0x08) { // GE + char
					if (n - i < 6)
						{ truncated(sb, start, "RA"); return; }
					chs = String.format("GE %02X", d[i + 5] & 0xFF);
					len = 6;
				} else {
					chs = byteName(ch);
				}
				line(sb, start, "RA", "repeat " + chs + " to " + addrStr(to, cols));
				i += len;
				break;
			}
			case 0x08: // GE
				if (n - i < 2)
					{ truncated(sb, start, "GE"); return; }
				line(sb, start, "GE", String.format("graphic escape char=%02X", d[i + 1] & 0xFF));
				i += 2;
				break;
			default: {
				int j = i;
				while (j < n && !isOrder(d[j] & 0xFF))
					j++;
				line(sb, start, "TEXT", textRuns(d, i, j));
				i = j;
			}
			}
		}
	}

	private static void decodeStructuredFields(StringBuilder sb, byte[] d, int i) {
		int n = d.length;
		while (i + 3 <= n) {
			int len = ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
			int id = d[i + 2] & 0xFF;
			int avail = n - i;
			if (len == 0)
				len = avail; // length 0 = rest of record
			String note = len > avail ? " (TRUNCATED, declared " + len + ", have " + avail + ")" : "";
			StringBuilder head = new StringBuilder();
			for (int k = 3; k < Math.min(len, avail) && k < 3 + 12; k++)
				head.append(String.format("%02X ", d[i + k] & 0xFF));
			line(sb, i, "SF", String.format("len=%d id=%02X (%s) %s%s", len, id, sfName(id), head.toString().trim(), note));
			if (len > avail || len < 3)
				return;
			i += len;
		}
	}

	// ======================================================================
	// Screen snapshot
	// ======================================================================

	static String renderScreen(ScreenModel sm) {
		int rows = sm.getRows();
		int cols = sm.getCols();
		int size = sm.getSize();
		StringBuilder sb = new StringBuilder(size + rows * 6 + 2048);
		int cur = sm.getCursorPos();
		sb.append(String.format("%dx%d  cursor=%s%n", rows, cols, addrStr(cur, cols)));

		// ruler
		sb.append("   ");
		for (int c = 1; c <= cols; c++)
			sb.append(c % 10 == 0 ? (char) ('0' + (c / 10) % 10) : ' ');
		sb.append('\n').append("   ");
		for (int c = 1; c <= cols; c++)
			sb.append((char) ('0' + c % 10));
		sb.append('\n');

		for (int r = 0; r < rows; r++) {
			sb.append(String.format("%02d|", r + 1));
			for (int c = 0; c < cols; c++) {
				int pos = r * cols + c;
				if (sm.getAttr(pos) != 0) {
					sb.append(ATTR_GLYPH);
				} else {
					sb.append(glyph(sm.getChar(pos)));
				}
			}
			sb.append("|\n");
		}

		// field table
		List<Integer> starts = new ArrayList<>();
		for (int i = 0; i < size; i++)
			if (sm.getAttr(i) != 0)
				starts.add(i);
		if (starts.isEmpty()) {
			sb.append("fields: none (unformatted screen)\n");
			return sb.toString();
		}
		sb.append("fields: ").append(starts.size()).append('\n');
		int listed = 0;
		for (int idx = 0; idx < starts.size(); idx++) {
			if (listed >= MAX_FIELDS_LISTED) {
				sb.append("  ... ").append(starts.size() - listed).append(" more field(s) not listed\n");
				break;
			}
			int s = starts.get(idx);
			int next = starts.get((idx + 1) % starts.size());
			int len = starts.size() == 1 ? size - 1 : ((next - s - 1) + size) % size;
			int attr = sm.getAttr(s) & 0xFF;

			StringBuilder content = new StringBuilder();
			int nonNul = 0;
			for (int k = 1; k <= len; k++) {
				char ch = sm.getChar((s + k) % size);
				if (ch != '\0')
					nonNul++;
				if (content.length() < 44)
					content.append(glyph(ch));
			}
			sb.append(String.format("  F%02d @%d %s attr=%02X [%s] len=%d nonNUL=%d \"%s\"%s", idx + 1, s,
					rcStr(s, cols), attr, attrFlags(attr), len, nonNul, content, len > 44 ? "..." : ""));

			if ((attr & 0x01) != 0) { // MDT
				if ((attr & 0x20) != 0) {
					sb.append("  <MDT but PROTECTED: excluded from Read Modified>");
				} else {
					// mirror sendAID(): trim leading and trailing NULs only (spaces are kept)
					int a = 1, z = len;
					while (a <= z && sm.getChar((s + a) % size) == '\0')
						a++;
					while (z >= a && sm.getChar((s + z) % size) == '\0')
						z--;
					StringBuilder send = new StringBuilder();
					for (int k = a; k <= z; k++)
						send.append(glyph(sm.getChar((s + k) % size)));
					String shown = send.length() > 60 ? send.substring(0, 60) + "..." : send.toString();
					sb.append("  RM would send ").append(Math.max(0, z - a + 1)).append(" byte(s) @")
							.append((s + a) % size).append(": \"").append(shown).append('"');
				}
			}
			sb.append('\n');
			listed++;
		}
		return sb.toString();
	}

	private static char glyph(char ch) {
		if (ch == '\0')
			return NUL_GLYPH;
		if (ch < 0x20 || ch >= 0x7F)
			return '?';
		return ch;
	}

	// ======================================================================
	// Formatting helpers
	// ======================================================================

	private static void hexDump(StringBuilder sb, byte[] d) {
		for (int off = 0; off < d.length; off += 16) {
			int end = Math.min(off + 16, d.length);
			StringBuilder hex = new StringBuilder();
			StringBuilder asc = new StringBuilder();
			for (int k = off; k < end; k++) {
				int v = d[k] & 0xFF;
				hex.append(String.format("%02X ", v));
				char c = EBCDIC_TO_ASCII[v];
				asc.append(c >= 0x20 && c < 0x7F ? c : '.');
			}
			sb.append(String.format("  %04X  %-48s |%s|%n", off, hex, asc));
		}
	}

	private static void line(StringBuilder sb, int off, String tag, String detail) {
		sb.append(String.format("    %04X  %-6s %s%n", off, tag, detail));
	}

	private static void truncated(StringBuilder sb, int off, String tag) {
		sb.append(String.format("    %04X  %-6s ** TRUNCATED %s operand - stream ends mid-order **%n", off, tag, tag));
	}

	private static void indent(StringBuilder sb, String block, String pad) {
		for (String ln : block.split("\n", -1)) {
			if (ln.isEmpty())
				continue;
			sb.append(pad).append(ln).append('\n');
		}
	}

	/** Same arithmetic as TN3270Session.decode3270Address, without the modulo. */
	static int decodeAddr(int b1, int b2) {
		int a = (b1 << 8) | b2;
		if ((b1 & 0xC0) != 0)
			a = ((b1 & 0x3F) << 6) | (b2 & 0x3F);
		return a;
	}

	private static String rcStr(int addr, int cols) {
		return cols > 0 ? "(r" + (addr / cols + 1) + ",c" + (addr % cols + 1) + ")" : "";
	}

	private static String addrStr(int addr, int cols) {
		return String.format("%d %s", addr, rcStr(addr, cols)).trim();
	}

	private static String byteName(int b) {
		if (b == 0x00)
			return "NUL";
		if (b == 0x40)
			return "SP";
		char c = EBCDIC_TO_ASCII[b];
		if (c > 0x20 && c < 0x7F)
			return "'" + c + "'";
		return String.format("<%02X>", b);
	}

	private static String textRuns(byte[] d, int from, int to) {
		StringBuilder out = new StringBuilder();
		StringBuilder lit = new StringBuilder();
		int i = from;
		while (i < to) {
			int b = d[i] & 0xFF;
			int run = 1;
			while (i + run < to && (d[i + run] & 0xFF) == b)
				run++;
			if (run >= 4) {
				flushLit(out, lit);
				if (out.length() > 0)
					out.append(' ');
				out.append(byteName(b)).append('x').append(run);
				i += run;
			} else {
				for (int k = 0; k < run; k++) {
					char c = EBCDIC_TO_ASCII[b];
					if (b == 0x40) {
						lit.append(' ');
					} else if (c > 0x20 && c < 0x7F) {
						if (c == '"')
							lit.append('\\');
						lit.append(c);
					} else {
						lit.append(String.format("<%02X>", b));
					}
				}
				i += run;
			}
		}
		flushLit(out, lit);
		return out.toString();
	}

	private static void flushLit(StringBuilder out, StringBuilder lit) {
		if (lit.length() == 0)
			return;
		if (out.length() > 0)
			out.append(' ');
		out.append('"').append(lit).append('"');
		lit.setLength(0);
	}

	static String attrFlags(int a) {
		StringBuilder s = new StringBuilder();
		s.append((a & 0x20) != 0 ? "PROT" : "UNPROT");
		if ((a & 0x10) != 0)
			s.append(" NUM");
		switch ((a >> 2) & 3) {
		case 2:
			s.append(" HI");
			break;
		case 3:
			s.append(" HIDDEN");
			break;
		default:
			break;
		}
		if ((a & 0x01) != 0)
			s.append(" MDT");
		return s.toString();
	}

	private static String pairString(int type, int val) {
		String name;
		String meaning = "";
		switch (type) {
		case 0xC0:
			name = "FIELD";
			meaning = " " + attrFlags(val);
			break;
		case 0x41:
			name = "HILITE";
			meaning = hiliteName(val);
			break;
		case 0x42:
			name = "FG";
			meaning = colorName(val);
			break;
		case 0x45:
			name = "BG";
			meaning = colorName(val);
			break;
		case 0x43:
			name = "CHARSET";
			meaning = val == 0xF1 ? " APL" : (val == 0 ? " default" : "");
			break;
		case 0x00:
			name = "ALL";
			meaning = val == 0 ? " reset-to-default" : "";
			break;
		default:
			name = "type";
			break;
		}
		return String.format("%s(%02X)=%02X%s", name, type, val, meaning);
	}

	private static String hiliteName(int v) {
		switch (v) {
		case 0x00: return " default";
		case 0xF0: return " normal";
		case 0xF1: return " blink";
		case 0xF2: return " reverse";
		case 0xF4: return " underscore";
		default: return "";
		}
	}

	private static String colorName(int v) {
		switch (v) {
		case 0x00: return " default";
		case 0xF1: return " blue";
		case 0xF2: return " red";
		case 0xF3: return " pink";
		case 0xF4: return " green";
		case 0xF5: return " turquoise";
		case 0xF6: return " yellow";
		case 0xF7: return " white";
		default: return "";
		}
	}

	private static String wccString(int w) {
		StringBuilder s = new StringBuilder(String.format("%02X", w));
		s.append(" [");
		List<String> f = new ArrayList<>();
		if ((w & 0x40) != 0) f.add("reset");
		if ((w & 0x08) != 0) f.add("start-printer");
		if ((w & 0x04) != 0) f.add("alarm");
		if ((w & 0x02) != 0) f.add("restore-keyboard");
		if ((w & 0x01) != 0) f.add("reset-MDT");
		s.append(f.isEmpty() ? "none" : String.join(", ", f)).append(']');
		return s.toString();
	}

	private static String commandName(int c) {
		switch (c) {
		case 0x01: case 0xF1: return "WRITE";
		case 0x05: case 0xF5: return "ERASE/WRITE";
		case 0x0D: case 0x7E: return "ERASE/WRITE ALTERNATE";
		case 0x02: case 0xF2: return "READ BUFFER";
		case 0x06: case 0xF6: return "READ MODIFIED";
		case 0x0E: case 0x6E: return "READ MODIFIED ALL";
		case 0x0F: case 0x6F: return "ERASE ALL UNPROTECTED";
		case 0x11: case 0xF3: return "WRITE STRUCTURED FIELD";
		default: return String.format("UNKNOWN %02X", c);
		}
	}

	private static String sfName(int id) {
		switch (id) {
		case 0x01: return "Read Partition";
		case 0x03: return "Erase/Reset";
		case 0x09: return "Set Reply Mode";
		case 0x0C: return "Create Partition";
		case 0x40: return "Outbound 3270DS";
		case 0xD0: return "Data Chain (file transfer)";
		case 0xA1: return "Query list";
		default: return "?";
		}
	}

	private static String tn3270eTypeName(int t) {
		switch (t) {
		case 0: return "3270-DATA";
		case 1: return "SCS-DATA";
		case 2: return "RESPONSE";
		case 3: return "BIND-IMAGE";
		case 4: return "UNBIND";
		case 5: return "NVT-DATA";
		case 6: return "REQUEST";
		case 7: return "SSCP-LU-DATA";
		case 8: return "PRINT-EOJ";
		default: return String.format("type-%02X", t);
		}
	}

	static String aidName(int a) {
		switch (a) {
		case 0x7D: return "ENTER";
		case 0x6D: return "CLEAR";
		case 0x6C: return "PA1";
		case 0x6E: return "PA2";
		case 0x6B: return "PA3";
		case 0xF0: return "SYSREQ";
		case 0x60: return "NO-AID";
		case 0x7E: return "CURSOR-SELECT";
		case 0x88: return "STRUCTURED-FIELD";
		case 0xF1: case 0xF2: case 0xF3: case 0xF4: case 0xF5:
		case 0xF6: case 0xF7: case 0xF8: case 0xF9:
			return "PF" + (a - 0xF0);
		case 0x7A: return "PF10";
		case 0x7B: return "PF11";
		case 0x7C: return "PF12";
		case 0xC1: case 0xC2: case 0xC3: case 0xC4: case 0xC5:
		case 0xC6: case 0xC7: case 0xC8: case 0xC9:
			return "PF" + (a - 0xC1 + 13);
		case 0x4A: return "PF22";
		case 0x4B: return "PF23";
		case 0x4C: return "PF24";
		default: return String.format("%02X", a);
		}
	}
}
