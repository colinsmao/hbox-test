package dev.kelianmao.mobwalk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins ADD insert-before → source index (clone lands below after swap).
 * TableRow/MaLiLib seeding is covered in-game (needs game version at runtime).
 */
final class CustomProfileTableRowsTest {
	@Test
	void clickedSourceIsRowBelowInsert() {
		// [known, NEW, known] — click + on second known
		boolean[] known = {true, false, true};
		assertEquals(2, CustomProfileTableRows.clickedSourceIndex(1, 3, known));
	}

	@Test
	void clickedSourceMissingWhenNewAtEnd() {
		boolean[] known = {true, false};
		assertEquals(-1, CustomProfileTableRows.clickedSourceIndex(1, 2, known));
	}

	@Test
	void clickedSourceMissingOnEmptyInsert() {
		boolean[] known = {false};
		assertEquals(-1, CustomProfileTableRows.clickedSourceIndex(0, 1, known));
	}
}
