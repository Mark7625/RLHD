/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * Copyright (c) 2026 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package rs117.hd.scene.model;

import java.nio.ByteBuffer;

/**
 * Reads only geometry-related fields from client config archives, without RuneLite's cache-tool dependency.
 * Opcode layouts follow RuneLite's ItemLoader and KitLoader.
 * Unknown opcodes fail explicitly: guessing their lengths would silently corrupt face offsets.
 */
final class PlayerModelDefinitions {
	private PlayerModelDefinitions() {}

	static int[] readWornModels(byte[] data, boolean female) {
		ByteBuffer in = ByteBuffer.wrap(data);
		int[] male = { -1, -1, -1 };
		int[] woman = { -1, -1, -1 };
		for (int op; (op = u8(in)) != 0;) {
			switch (op) {
				case 23: male[0] = u16(in); skip(in, 1); break;
				case 24: male[1] = u16(in); break;
				case 25: woman[0] = u16(in); skip(in, 1); break;
				case 26: woman[1] = u16(in); break;
				case 78: male[2] = u16(in); break;
				case 79: woman[2] = u16(in); break;
				case 45: male[0] = in.getInt(); skip(in, 1); break;
				case 46: male[1] = in.getInt(); break;
				case 47: male[2] = in.getInt(); break;
				case 48: woman[0] = in.getInt(); skip(in, 1); break;
				case 49: woman[1] = in.getInt(); break;
				case 50: woman[2] = in.getInt(); break;
				case 2: case 3: case 9:
				case 30: case 31: case 32: case 33: case 34:
				case 35: case 36: case 37: case 38: case 39:
					skipString(in);
					break;
				case 1: case 4: case 5: case 6: case 7: case 8: case 75:
				case 90: case 91: case 92: case 93: case 94: case 95:
				case 97: case 98: case 99: case 110: case 111: case 112:
				case 139: case 140: case 148: case 149:
					skip(in, 2);
					break;
				case 13: case 14: case 27: case 42: case 113: case 114: case 115:
					skip(in, 1);
					break;
				case 12: case 44: case 51: case 52: case 53: case 54:
				case 100: case 101: case 102: case 103: case 104:
				case 105: case 106: case 107: case 108: case 109:
					skip(in, 4);
					break;
				case 11: case 15: case 16: case 65: case 160: case 251:
					break;
				case 40: case 41:
					skip(in, u8(in) * 4);
					break;
				case 43:
					skip(in, 1);
					while (u8(in) != 0)
						skipString(in);
					break;
				case 161:
					skip(in, u16(in) * 2);
					break;
				case 200: // Sub-operation: index, sub-index, text.
					skip(in, 2);
					skipString(in);
					break;
				case 201: // Conditional operation: index, varp, varbit, min, max, text.
					skip(in, 13);
					skipString(in);
					break;
				case 202: // Conditional sub-operation additionally has a ushort sub-index.
					skip(in, 15);
					skipString(in);
					break;
				case 249:
					int count = u8(in);
					for (int i = 0; i < count; i++) {
						boolean string = u8(in) == 1;
						skip(in, 3);
						if (string) {
							skipString(in);
						} else {
							skip(in, 4);
						}
					}
					break;
				default:
					throw unknown("item", op);
			}
		}
		return female ? woman : male;
	}

	static int[] readKitModels(byte[] data) {
		ByteBuffer in = ByteBuffer.wrap(data);
		int[] models = null;
		for (int op; (op = u8(in)) != 0;) {
			switch (op) {
				case 1: skip(in, 1); break;
				case 2: case 5:
					models = new int[u8(in)];
					for (int i = 0; i < models.length; i++)
						models[i] = op == 2 ? u16(in) : in.getInt();
					break;
				case 3: break;
				case 40: case 41: skip(in, u8(in) * 4); break;
				default:
					if (op >= 60 && op < 70) {
						skip(in, 2);
					} else if (op >= 70 && op < 80) {
						skip(in, 4);
					} else {
						throw unknown("kit", op);
					}
			}
		}
		return models;
	}

	private static int u8(ByteBuffer in) {
		return in.get() & 255;
	}

	private static int u16(ByteBuffer in) {
		return in.getShort() & 65535;
	}

	private static void skip(ByteBuffer in, int bytes) {
		in.position(in.position() + bytes);
	}

	private static void skipString(ByteBuffer in) {
		while (in.get() != 0) {}
	}

	private static IllegalArgumentException unknown(String type, int opcode) {
		return new IllegalArgumentException("Unsupported " + type + " opcode " + opcode);
	}
}
