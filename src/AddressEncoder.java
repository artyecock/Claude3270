/**
 * 3270 address encoding and decoding utilities. Handles both 12-bit and 14-bit
 * addressing modes.
 */
public class AddressEncoder {
	/**
	 * Decode a 3270 address from two bytes. Supports both 12-bit and 14-bit
	 * addressing.
	 * 
	 * @param b1         First address byte
	 * @param b2         Second address byte
	 * @param bufferSize Total buffer size (rows * cols)
	 * @return Decoded buffer position
	 */
	public static int decode3270Address(byte b1, byte b2, int bufferSize) {
		int addr;
		int b1val = b1 & 0xFF;
		int b2val = b2 & 0xFF;

		// Check for 14-bit addressing: if high byte >= 0x40, it's 12-bit
		// If high byte < 0x40, it's 14-bit
		if ((b1val & 0xC0) == 0) {
			// 14-bit addressing: straight binary
			addr = (b1val << 8) | b2val;
		} else {
			// 12-bit addressing: remove 0x40 offset and combine 6+6 bits
			addr = ((b1val & 0x3F) << 6) | (b2val & 0x3F);
		}

		return addr % bufferSize;
	}

	/**
	 * Encode a buffer position to 3270 address bytes. Uses 14-bit addressing for
	 * addresses >= 4096, otherwise 12-bit.
	 * 
	 * @param addr Buffer position to encode
	 * @return Two-byte array containing encoded address
	 */
	public static byte[] encode3270Address(int addr) {
		byte[] result = new byte[2];

		// Ensure address is within 14-bit range
		addr = addr & 0x3FFF;

		// IBM standard: addresses >= 4096 require 14-bit addressing
		if (addr >= 0x1000) {
			// 14-bit addressing: straight binary
			result[0] = (byte) ((addr >> 8) & 0xFF);
			result[1] = (byte) (addr & 0xFF);
		} else {
			// 12-bit addressing: use EBCDIC address translation
			int high6 = (addr >> 6) & 0x3F;
			int low6 = addr & 0x3F;
			result[0] = Constants.ADDRESS_TABLE[high6];
			result[1] = Constants.ADDRESS_TABLE[low6];
		}

		return result;
	}
}
