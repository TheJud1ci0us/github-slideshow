# Development tool: pip install genanki; run from the ascolta-android directory.
import sys,zipfile,sqlite3,os
import genanki
fields=[{'name':n} for n in ['Word','Meaning','Sentence','SentenceMeaning','Audio']]
model=genanki.Model(1700420001,'LingoPod vocabulary',fields=fields,templates=[{'name':'Vocabulary','qfmt':'{{Word}}<br><br>{{Sentence}}','afmt':'{{FrontSide}}<hr id="answer">{{Meaning}}<br><br>{{SentenceMeaning}}<br>{{Audio}}'}],css='.card { font-family: sans-serif; font-size: 24px; text-align: center; }')
deck=genanki.Deck(1700420002,'LingoPod');deck.add_model(model)
# genanki writes models only for notes: include then remove a seed note.
deck.add_note(genanki.Note(model=model,fields=['sample','meaning','','','']))
genanki.Package(deck).write_to_file('/tmp/anki-template.apkg')
out='app/src/main/assets/anki-template.anki2'
with zipfile.ZipFile('/tmp/anki-template.apkg') as z:open(out,'wb').write(z.read('collection.anki2'))
c=sqlite3.connect(out)
for table in ['notes','cards','revlog','graves']:c.execute('DELETE FROM '+table)
c.commit();c.execute('VACUUM');c.close()
