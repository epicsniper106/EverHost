package dev.everhost.universal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free JSON parser used for CurseForge and loader metadata. */
public final class MiniJson {
	private final String source;
	private int index;
	private int depth;

	private MiniJson(String source) {
		this.source = source;
	}

	static Object parse(Path path) throws IOException {
		return parse(Files.readString(path, StandardCharsets.UTF_8));
	}

	public static Object parse(String source) throws IOException {
		MiniJson parser = new MiniJson(source);
		Object value = parser.readValue();
		parser.skipWhitespace();
		if (parser.index != source.length()) {
			throw parser.error("Unexpected trailing data");
		}
		return value;
	}

	private Object readValue() throws IOException {
		if (++depth > 64) throw error("JSON nesting is too deep");
		try {
		skipWhitespace();
		if (index >= source.length()) {
			throw error("Unexpected end of JSON");
		}
		return switch (source.charAt(index)) {
			case '{' -> readObject();
			case '[' -> readArray();
			case '"' -> readString();
			case 't' -> readLiteral("true", Boolean.TRUE);
			case 'f' -> readLiteral("false", Boolean.FALSE);
			case 'n' -> readLiteral("null", null);
			default -> readNumber();
		};
		} finally { depth--; }
	}

	private Map<String, Object> readObject() throws IOException {
		Map<String, Object> result = new LinkedHashMap<>();
		index++;
		skipWhitespace();
		if (consume('}')) {
			return result;
		}
		while (true) {
			skipWhitespace();
			if (index >= source.length() || source.charAt(index) != '"') {
				throw error("Expected object key");
			}
			String key = readString();
			if (result.containsKey(key)) throw error("Duplicate object key");
			skipWhitespace();
			expect(':');
			result.put(key, readValue());
			skipWhitespace();
			if (consume('}')) {
				return result;
			}
			expect(',');
		}
	}

	private List<Object> readArray() throws IOException {
		List<Object> result = new ArrayList<>();
		index++;
		skipWhitespace();
		if (consume(']')) {
			return result;
		}
		while (true) {
			result.add(readValue());
			skipWhitespace();
			if (consume(']')) {
				return result;
			}
			expect(',');
		}
	}

	private String readString() throws IOException {
		expect('"');
		StringBuilder result = new StringBuilder();
		while (index < source.length()) {
			char character = source.charAt(index++);
			if (character == '"') {
				return result.toString();
			}
			if (character != '\\') {
				if (character < 32) throw error("Unescaped control character");
				result.append(character);
				continue;
			}
			if (index >= source.length()) {
				throw error("Unterminated escape sequence");
			}
			char escaped = source.charAt(index++);
			switch (escaped) {
				case '"', '\\', '/' -> result.append(escaped);
				case 'b' -> result.append('\b');
				case 'f' -> result.append('\f');
				case 'n' -> result.append('\n');
				case 'r' -> result.append('\r');
				case 't' -> result.append('\t');
				case 'u' -> result.append(readUnicode());
				default -> throw error("Invalid escape sequence");
			}
		}
		throw error("Unterminated string");
	}

	private char readUnicode() throws IOException {
		if (index + 4 > source.length()) {
			throw error("Incomplete Unicode escape");
		}
		try {
			char value = (char)Integer.parseInt(source.substring(index, index + 4), 16);
			index += 4;
			return value;
		} catch (NumberFormatException exception) {
			throw error("Invalid Unicode escape");
		}
	}

	private Object readNumber() throws IOException {
		int start = index;
		if (peek('-')) {
			index++;
		}
		while (index < source.length() && Character.isDigit(source.charAt(index))) {
			index++;
		}
		if (peek('.')) {
			index++;
			while (index < source.length() && Character.isDigit(source.charAt(index))) {
				index++;
			}
		}
		if (peek('e') || peek('E')) {
			index++;
			if (peek('+') || peek('-')) {
				index++;
			}
			while (index < source.length() && Character.isDigit(source.charAt(index))) {
				index++;
			}
		}
		String number = source.substring(start, index);
		if (number.isEmpty() || "-".equals(number)) {
			throw error("Expected a JSON value");
		}
		try {
			return number.contains(".") || number.contains("e") || number.contains("E")
				? Double.parseDouble(number)
				: Long.parseLong(number);
		} catch (NumberFormatException exception) {
			throw error("Invalid number");
		}
	}

	private Object readLiteral(String literal, Object value) throws IOException {
		if (!source.startsWith(literal, index)) {
			throw error("Invalid literal");
		}
		index += literal.length();
		return value;
	}

	private void skipWhitespace() {
		while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
			index++;
		}
	}

	private void expect(char expected) throws IOException {
		if (!consume(expected)) {
			throw error("Expected '" + expected + "'");
		}
	}

	private boolean consume(char expected) {
		if (index < source.length() && source.charAt(index) == expected) {
			index++;
			return true;
		}
		return false;
	}

	private boolean peek(char expected) {
		return index < source.length() && source.charAt(index) == expected;
	}

	private IOException error(String message) {
		return new IOException(message + " at character " + index);
	}
}
