Parental Control - Simplu (JavaFX)

Această aplicație JavaFX permite părinților să blocheze executabile, să seteze limite zilnice de utilizare, intervale orare permise și să blocheze site-uri web. Listele sunt salvate în %APPDATA%/ParentalControlApp și pot fi protejate cu PIN.

Funcționalități:
- Adăugare/ editare aplicații blocate cu buton „Răsfoiește...”
- Limită zilnică de timp și intervale permise
- Monitorizare procese Windows cu blocare automată
- Tab pentru site-uri: adăugare, editare, ștergere și aplicare blocare în fișierul `hosts`
- PIN opțional pentru acțiuni sensibile

Cerințe:
- JDK 11+ și JavaFX SDK configurat pe module path
- Drepturi de Administrator pentru a modifica fișierul `hosts` (blocare site-uri)

Rulare (PowerShell):
```powershell
javac --module-path "C:\javafx-sdk-XX\lib" --add-modules javafx.controls,javafx.fxml -d out src\*.java
java --module-path "C:\javafx-sdk-XX\lib" --add-modules javafx.controls,javafx.fxml -cp out Main
```

Note:
- Blocarea site-urilor implică marcaje `# BEGIN/END PARENTAL_CONTROL` în `hosts`. Utilizați butonul „Aplică blocare” din tab-ul Site-uri după orice modificare.
- Dacă fișierul `hosts` nu poate fi scris, rulați aplicația ca Administrator.
- PIN-ul nu este criptat și trebuie reținut manual.
