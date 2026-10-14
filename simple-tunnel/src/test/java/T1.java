public class T1 {

    public static void main(String[] args) {

    }



    public int editDistance(String word1, String word2) {
        if (word1 == null || word2 == null) {
            throw new IllegalArgumentException("Words must not be null");
        }

        // Keep the DP row as small as possible.
        if (word1.length() < word2.length()) {
            String temporary = word1;
            word1 = word2;
            word2 = temporary;
        }

        int[] previous = new int[word2.length() + 1];
        int[] current = new int[word2.length() + 1];
        for (int j = 0; j <= word2.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= word1.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= word2.length(); j++) {
                int substitutionCost = word1.charAt(i - 1) == word2.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(previous[j] + 1, current[j - 1] + 1),
                        previous[j - 1] + substitutionCost);
            }

            int[] temporary = previous;
            previous = current;
            current = temporary;
        }

        return previous[word2.length()];
    }
}
