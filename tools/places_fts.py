"""Build the FTS4 prefix index used by the Android offline place search."""

def create_fts_index(db):
    db.execute("drop table if exists places_fts")
    db.execute("create virtual table places_fts using fts4(s, tokenize=unicode61)")
    db.execute("insert into places_fts(docid,s) select id,s from places")
    count = db.execute("select count(*) from places_fts").fetchone()[0]
    places = db.execute("select count(*) from places").fetchone()[0]
    if count != places:
        raise RuntimeError("FTS index row count does not match places table")
