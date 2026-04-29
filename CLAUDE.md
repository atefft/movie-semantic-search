# Movie Semantic Search

## Test commands

Run only the tests relevant to the files you changed.

### Python (pipeline/)
```bash
python3 -m pytest pipeline/tests/ -v
```

### Java (api/)
```bash
./mvnw -f api/pom.xml test
```

### General rule
- Changed files under `pipeline/` → run the Python command.
- Changed files under `api/` → run the Java command.
- Changed files in both → run both.
