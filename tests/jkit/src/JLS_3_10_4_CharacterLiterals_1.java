class JLS_3_10_4_CharacterLiterals_1 {
    public static void main(String[] args) {
	char a = '\b';

	char[] escapes = {
	    '\b',
	    '\t',
	    '\n',
	    '\f',
	    '\r',
	    '\"',
	    '\'',
	    '\\',
	    '\0',
	    '\1',
	    '\2',
	    '\3',
	    '\4',
	    '\5',
	    '\6',
	    '\7',
	    '\10',
	    '\11',
	    '\12',
	    '\13',
	    '\14',
	    '\15',
	    '\16',
	    '\17',
	    '\20',
	    '\21',
	    '\22',
	    '\23',
	    '\24',
	    '\25',
	    '\26',
	    '\27',
	    '\31',
	    '\32',
	    '\33',
	    '\34',
	    '\35',
	    '\36',
	    '\37',
	    '\30',
	    '\41',
	    '\42',
	    '\43',
	    '\44',
	    '\45',
	    '\46',
	    '\47'};

	for(char x : escapes) {
	    System.out.println("***" + x + "***");
	}
    }
}
