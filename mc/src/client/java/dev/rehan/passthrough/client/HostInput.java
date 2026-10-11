package dev.rehan.passthrough.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.rehan.passthrough.Passthrough;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * The host's keyboard and mouse, fed to Minecraft as if its own window had them (its inventory, the creative menu,
 * chat, commands and every other screen). The host sends Windows virtual keys; Minecraft (SDL) wants USB HID scancodes.
 * Client thread.
 *
 * <ul>
 * <li>{"t":"rawkey","vk":code,"sc":scancode,"ext":bool,"down":bool,"rep":bool,"mods":SDL modifier bits}</li>
 * <li>{"t":"char","c":codepoint}: typed text (only while a screen is open)</li>
 * <li>{"t":"mouse","x":0..1,"y":0..1}: the host's cursor over its picture (= Minecraft's window)</li>
 * <li>{"t":"mbtn","b":1 left|2 middle|3 right,"down":bool,"mods":bits}, {"t":"mscroll","d":+-1}</li>
 * </ul>
 * Back to the host: {"t":"screen","open":bool,"name":class}: whether a Minecraft screen wants all the input.
 */
final class HostInput {
	private static boolean screenSent;
	private static int screenResendIn;

	private HostInput() {
	}

	static void handle(final Minecraft minecraft, final JsonObject m) {
		long window = minecraft.getWindow().handle();
		switch (m.get("t").getAsString()) {
			case "rawkey" -> {
				int key = hid(m.get("vk").getAsInt(), m.has("sc") ? m.get("sc").getAsInt() : 0, m.has("ext") && m.get("ext").getAsBoolean());
				if (key == 0) {
					return;
				}

				boolean down = m.get("down").getAsBoolean();
				int action = !down ? InputConstants.RELEASE : m.has("rep") && m.get("rep").getAsBoolean() ? InputConstants.REPEAT : InputConstants.PRESS;
				minecraft.keyboardHandler.keyPress(window, action, new KeyEvent(key, keycode(key), mods(m)));
			}
			case "char" -> {
				int c = m.get("c").getAsInt();
				if (minecraft.gui.screen() != null && Character.isValidCodePoint(c)) {
					minecraft.keyboardHandler.charTyped(window, new CharacterEvent(c));
				}
			}
			case "mouse" -> {
				Window w = minecraft.getWindow();
				double x = Math.clamp(m.get("x").getAsDouble(), 0.0, 1.0) * w.getScreenWidth();
				double y = Math.clamp(m.get("y").getAsDouble(), 0.0, 1.0) * w.getScreenHeight();
				minecraft.mouseHandler.onMove(window, x, y, x - minecraft.mouseHandler.xpos(), y - minecraft.mouseHandler.ypos());
			}
			case "mbtn" -> minecraft.mouseHandler.onButton(window, new MouseButtonInfo(m.get("b").getAsInt(), mods(m)),
				m.get("down").getAsBoolean() ? InputConstants.PRESS : InputConstants.RELEASE);
			case "mscroll" -> minecraft.mouseHandler.onScroll(window, 0.0, m.get("d").getAsDouble());
			default -> {
			}
		}
	}

	/** The host went away (or stopped sending): every key and button it held is let go. Client thread. */
	static void releaseAll() {
		net.minecraft.client.KeyMapping.releaseAll();
		Minecraft minecraft = Minecraft.getInstance();
		long window = minecraft.getWindow().handle();
		for (int b = 1; b <= 3; b++) {
			minecraft.mouseHandler.onButton(window, new MouseButtonInfo(b, 0), InputConstants.RELEASE);
		}
	}

	/** Every client tick: tell the host when a screen opens or closes (and now and then anyway). */
	static void tick(final Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		// in the world only: the title and loading screens must never take the host's keyboard
		boolean open = screen != null && minecraft.player != null && minecraft.level != null && !(screen instanceof DeathScreen);
		if (open != screenSent || --screenResendIn <= 0) {
			screenSent = open;
			screenResendIn = 20;
			Passthrough.events.accept("{\"t\":\"screen\",\"open\":" + open + ",\"name\":\"" + (screen == null ? "" : screen.getClass().getSimpleName()) + "\"}");
		}
	}

	private static int mods(final JsonObject m) {
		return m.has("mods") ? m.get("mods").getAsInt() : 0;
	}

	/** SDL keycode for a scancode: the character for printable keys (US layout), else the scancode | 1 << 30. */
	private static int keycode(final int key) {
		if (key >= 4 && key <= 29) {
			return 'a' + key - 4;
		}

		if (key >= 30 && key <= 38) {
			return '1' + key - 30;
		}

		return switch (key) {
			case 39 -> '0';
			case 40 -> '\r';
			case 41 -> 27;
			case 42 -> '\b';
			case 43 -> '\t';
			case 44 -> ' ';
			case 45 -> '-';
			case 46 -> '=';
			case 47 -> '[';
			case 48 -> ']';
			case 49 -> '\\';
			case 51 -> ';';
			case 52 -> '\'';
			case 53 -> '`';
			case 54 -> ',';
			case 55 -> '.';
			case 56 -> '/';
			case 76 -> 127;
			default -> key | 1 << 30;
		};
	}

	/** Windows virtual key (with its scancode and extended flag, for left/right modifiers) -> USB HID usage. */
	static int hid(final int vk, final int sc, final boolean ext) {
		if (vk >= 'A' && vk <= 'Z') {
			return 4 + vk - 'A';
		}

		if (vk >= '1' && vk <= '9') {
			return 30 + vk - '1';
		}

		if (vk >= 0x70 && vk <= 0x7B) {
			return 58 + vk - 0x70; // F1..F12
		}

		if (vk >= 0x61 && vk <= 0x69) {
			return 89 + vk - 0x61; // numpad 1..9
		}

		return switch (vk) {
			case '0' -> 39;
			case 0x0D -> ext ? 88 : 40; // enter (numpad enter)
			case 0x1B -> 41;
			case 0x08 -> 42;
			case 0x09 -> 43;
			case 0x20 -> 44;
			case 0xBD -> 45; // - _
			case 0xBB -> 46; // = +
			case 0xDB -> 47; // [ {
			case 0xDD -> 48; // ] }
			case 0xDC -> 49; // \ |
			case 0xBA -> 51; // ; :
			case 0xDE -> 52; // ' "
			case 0xC0 -> 53; // ` ~
			case 0xBC -> 54; // , <
			case 0xBE -> 55; // . >
			case 0xBF -> 56; // / ?
			case 0x14 -> 57; // caps lock
			case 0x2C -> 70; // print screen
			case 0x91 -> 71; // scroll lock
			case 0x13 -> 72; // pause
			case 0x2D -> 73; // insert
			case 0x24 -> 74; // home
			case 0x21 -> 75; // page up
			case 0x2E -> 76; // delete
			case 0x23 -> 77; // end
			case 0x22 -> 78; // page down
			case 0x27 -> 79; // right
			case 0x25 -> 80; // left
			case 0x28 -> 81; // down
			case 0x26 -> 82; // up
			case 0x90 -> 83; // num lock
			case 0x6F -> 84; // numpad /
			case 0x6A -> 85; // numpad *
			case 0x6D -> 86; // numpad -
			case 0x6B -> 87; // numpad +
			case 0x60 -> 98; // numpad 0
			case 0x6E -> 99; // numpad .
			case 0x10 -> sc == 0x36 ? 229 : 225; // shift: right / left
			case 0xA0 -> 225;
			case 0xA1 -> 229;
			case 0x11 -> ext ? 228 : 224; // ctrl: right / left
			case 0xA2 -> 224;
			case 0xA3 -> 228;
			case 0x12 -> ext ? 230 : 226; // alt: right / left
			case 0xA4 -> 226;
			case 0xA5 -> 230;
			case 0x5B -> 227; // windows keys
			case 0x5C -> 231;
			default -> 0;
		};
	}
}
