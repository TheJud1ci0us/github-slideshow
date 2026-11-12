"""Builds app/src/main/assets/words/<lang>.txt: the most common words of English and of the
European languages Parakeet covers (wordfreq, CC BY-SA 4.0), one per line, most frequent first.
Used to spot sentences Parakeet wrote in English instead of the episode's language.
Run: pip install wordfreq && python3 tools/build_wordlists.py"""
import os, re, wordfreq

PARAKEET = ["bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it",
            "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk"]
# Also Japanese, Chinese and Korean: for colouring transcript words by how common they are.
PARAKEET += ["ja", "zh", "ko"]
COUNT = 25_000
out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "words")
os.makedirs(out, exist_ok=True)
avail = set(wordfreq.available_languages())
total = 0
for lang in PARAKEET:
    if lang not in avail:
        print("skip", lang); continue
    words = [w for w in wordfreq.top_n_list(lang, COUNT * 2) if re.fullmatch(r"[^\W\d_]+(['’-][^\W\d_]+)*", w)][:COUNT]
    data = "\n".join(words).encode()
    path = os.path.join(out, f"{lang}.txt")  # plain: the build would unpack and rename a .gz
    with open(path, "wb") as f: f.write(data)
    total += os.path.getsize(path)
    print(lang, len(words), os.path.getsize(path))
print("total", total)

# For spotting misheard words (DoubtfulWords): the next 75,000 words of the languages where
# that leaves under 0.5 % of correct words unexplained (measured on wordfreq's large lists).
MORE = ["sv", "de", "fr", "es", "it", "nl", "pt", "en"]
more = os.path.join(out, "..", "words-more")
os.makedirs(more, exist_ok=True)
for lang in MORE:
    words = [w for w in wordfreq.top_n_list(lang, 400_000) if re.fullmatch(r"[^\W\d_]+(['’-][^\W\d_]+)*", w)][COUNT:100_000]
    with open(os.path.join(more, f"{lang}.txt"), "wb") as f: f.write("\n".join(words).encode())
    print("more", lang, len(words))
