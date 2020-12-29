package com.bytezone.diskbrowser.applefile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bytezone.diskbrowser.utilities.HexFormatter;
import com.bytezone.diskbrowser.utilities.Utility;

// -----------------------------------------------------------------------------------//
public class ApplesoftBasicProgram extends BasicProgram
// -----------------------------------------------------------------------------------//
{
  private static final byte TOKEN_FOR = (byte) 0x81;
  private static final byte TOKEN_NEXT = (byte) 0x82;
  private static final byte TOKEN_DATA = (byte) 0x83;
  private static final byte TOKEN_INPUT = (byte) 0x84;
  private static final byte TOKEN_DIM = (byte) 0x86;
  private static final byte TOKEN_LET = (byte) 0xAA;
  private static final byte TOKEN_GOTO = (byte) 0xAB;
  private static final byte TOKEN_IF = (byte) 0xAD;
  private static final byte TOKEN_GOSUB = (byte) 0xB0;
  private static final byte TOKEN_RETURN = (byte) 0xB1;
  private static final byte TOKEN_REM = (byte) 0xB2;
  private static final byte TOKEN_ON = (byte) 0xB4;
  private static final byte TOKEN_PRINT = (byte) 0xBA;
  private static final byte TOKEN_THEN = (byte) 0xC4;
  private static final byte TOKEN_EQUALS = (byte) 0xD0;

  private final List<SourceLine> sourceLines = new ArrayList<> ();
  private final int endPtr;
  private final Map<Integer, List<Integer>> gotoLines = new TreeMap<> ();
  private final Map<Integer, List<Integer>> gosubLines = new TreeMap<> ();
  private final Map<String, List<Integer>> symbolLines = new TreeMap<> ();
  private final Map<String, List<String>> uniqueSymbols = new TreeMap<> ();
  private final List<Integer> stringsLine = new ArrayList<> ();
  private final List<String> stringsText = new ArrayList<> ();

  // ---------------------------------------------------------------------------------//
  public ApplesoftBasicProgram (String name, byte[] buffer)
  // ---------------------------------------------------------------------------------//
  {
    super (name, buffer);

    int ptr = 0;
    int prevOffset = 0;

    int max = buffer.length - 6;          // need at least 6 bytes to make a SourceLine
    while (ptr <= max)
    {
      int nextAddress = Utility.unsignedShort (buffer, ptr);
      if (nextAddress <= prevOffset)           // usually zero
        break;

      SourceLine line = new SourceLine (ptr);
      sourceLines.add (line);
      ptr += line.length;
      prevOffset = nextAddress;
    }
    endPtr = ptr;
  }

  // ---------------------------------------------------------------------------------//
  @Override
  public String getText ()
  // ---------------------------------------------------------------------------------//
  {
    return showDebugText ? getHexText () : getProgramText ();
  }

  // ---------------------------------------------------------------------------------//
  private String getProgramText ()
  // ---------------------------------------------------------------------------------//
  {
    int indentSize = 2;
    boolean insertBlankLine = false;

    StringBuilder fullText = new StringBuilder ();
    Stack<String> loopVariables = new Stack<> ();
    if (basicPreferences.showHeader)
      addHeader (fullText);
    int alignPos = 0;
    StringBuilder text;
    int baseOffset = basicPreferences.showTargets ? 12 : 8;

    for (SourceLine line : sourceLines)
    {
      text = new StringBuilder (getBase (line) + "  ");

      int indent = loopVariables.size ();   // each full line starts at the loop indent
      int ifIndent = 0;                     // IF statement(s) limit back indentation by NEXT

      for (SubLine subline : line.sublines)
      {
        // Allow empty statements (caused by a single colon)
        if (subline.isEmpty ())
          continue;

        // A REM statement might conceal an assembler routine
        // - see P.CREATE on Diags2E.DSK
        if (subline.is (TOKEN_REM) && subline.containsToken ())
        {
          int address = subline.getAddress () + 1;              // skip the REM token
          fullText.append (text + String.format ("REM - Inline assembler @ $%02X (%d)%n",
              address, address));
          String padding = "                         ".substring (0, text.length () + 2);
          for (String asm : subline.getAssembler ())
            fullText.append (padding + asm + "\n");
          continue;
        }

        // Beagle Bros often have multiline REM statements
        if (subline.is (TOKEN_REM) && subline.containsControlChars ())
        {
          subline.addFormattedRem (text);
          fullText.append (text + "\n");
          continue;
        }

        // Reduce the indent by each NEXT, but only as far as the IF indent allows
        if (subline.is (TOKEN_NEXT))
        {
          popLoopVariables (loopVariables, subline);
          indent = Math.max (ifIndent, loopVariables.size ());
        }

        // Are we joining REM lines with the previous subline?
        if (!basicPreferences.splitRem && subline.isJoinableRem ())
        {
          // Join this REM statement to the previous line, so no indenting
          fullText.deleteCharAt (fullText.length () - 1);         // remove newline
          fullText.append (" ");
        }
        else    // ... otherwise do all the indenting and showing of targets etc.
        {
          // Prepare target indicators for subsequent sublines (ie no line number)
          if (basicPreferences.showTargets && !subline.isFirst ())
            if (subline.is (TOKEN_GOSUB)
                || (subline.is (TOKEN_ON) && subline.has (TOKEN_GOSUB)))
              text.append ("<<--");
            else if (subline.is (TOKEN_GOTO) || subline.isImpliedGoto ()
                || (subline.is (TOKEN_ON) && subline.has (TOKEN_GOTO)))
              text.append (" <--");

          // Align assign statements if required
          if (basicPreferences.alignAssign)
            alignPos = alignEqualsPosition (subline, alignPos);

          int column = indent * indentSize + baseOffset;
          while (text.length () < column)
            text.append (" ");
        }

        // Add the current text, then reset it
        int pos = subline.is (TOKEN_REM) ? 0 : alignPos;
        String lineText = subline.getAlignedText (pos);

        if (subline.is (TOKEN_REM) && basicPreferences.deleteExtraRemSpace)
          lineText = lineText.replaceFirst ("REM  ", "REM ");

        if (subline.is (TOKEN_DATA) && basicPreferences.deleteExtraDataSpace)
          lineText = lineText.replaceFirst ("DATA  ", "DATA ");

        // Check for a wrappable REM statement
        // (see SEA BATTLE on DISK283.DSK)
        int inset = Math.max (text.length (), getIndent (fullText)) + 1;
        if (subline.is (TOKEN_REM) && lineText.length () > basicPreferences.wrapRemAt)
        {
          List<String> lines = splitLine (lineText, basicPreferences.wrapRemAt, ' ');
          addSplitLines (lines, text, inset);
        }
        else if (subline.is (TOKEN_DATA)
            && lineText.length () > basicPreferences.wrapDataAt)
        {
          List<String> lines = splitLine (lineText, basicPreferences.wrapDataAt, ',');
          addSplitLines (lines, text, inset);
        }
        else if (subline.is (TOKEN_DIM) && basicPreferences.splitDim)
        {
          List<String> lines = splitDim (lineText);
          addSplitLines (lines, text, inset);
        }
        else
          text.append (lineText);

        // Check for a wrappable PRINT statement
        // (see FROM MACHINE LANGUAGE TO BASIC on DOSToolkit2eB.dsk)
        if (basicPreferences.wrapPrintAt > 0           //
            && (subline.is (TOKEN_PRINT) || subline.is (TOKEN_INPUT))
            && countChars (text, ASCII_QUOTE) == 2        // just start and end quotes
            && countChars (text, ASCII_CARET) == 0)       // no control characters
        //    && countChars (text, ASCII_SEMI_COLON) == 0)
        {
          if (true)       // new method
          {
            List<String> lines = splitPrint (lineText);
            if (lines != null)
            {
              int offset = text.indexOf ("PRINT");
              if (offset < 0)
                offset = text.indexOf ("INPUT");
              String fmt = "%-" + offset + "." + offset + "s%s%n";
              String padding = text.substring (0, offset);
              for (String s : lines)
              {
                fullText.append (String.format (fmt, padding, s));
                padding = "";
              }
            }
            else
              fullText.append (text + "\n");
          }
          else            // old method
          {
            int first = text.indexOf ("\"") + 1;
            int last = text.indexOf ("\"", first + 1) - 1;
            if ((last - first) > basicPreferences.wrapPrintAt)
            {
              int ptr = first + basicPreferences.wrapPrintAt;
              do
              {
                fullText.append (text.substring (0, ptr)
                    + "\n                                 ".substring (0, first + 1));
                text.delete (0, ptr);
                ptr = basicPreferences.wrapPrintAt;
              } while (text.length () > basicPreferences.wrapPrintAt);
            }
            fullText.append (text + "\n");
          }
        }
        else
          fullText.append (text + "\n");

        text.setLength (0);

        // Calculate indent changes that take effect after the current subline
        if (subline.is (TOKEN_IF))
          ifIndent = ++indent;
        else if (subline.is (TOKEN_FOR))
        {
          loopVariables.push (subline.forVariable);
          ++indent;
        }
        else if (basicPreferences.blankAfterReturn && subline.is (TOKEN_RETURN))
          insertBlankLine = true;
      }

      if (insertBlankLine)
      {
        fullText.append ("\n");
        insertBlankLine = false;
      }

      // Reset alignment value if we just left an IF - the indentation will be different now
      if (ifIndent > 0)
        alignPos = 0;
    }

    int ptr = endPtr + 2;
    if (ptr < buffer.length - 1)    // sometimes there's an extra byte on the end
    {
      int offset = Utility.unsignedShort (buffer, 0);
      int programLoadAddress = offset - getLineLength (0);
      fullText.append ("\nExtra data:\n\n");
      fullText.append (HexFormatter.formatNoHeader (buffer, ptr, buffer.length - ptr,
          programLoadAddress + ptr));
      fullText.append ("\n");
    }

    if (basicPreferences.showXref && !gosubLines.isEmpty ())
      showLines (fullText, gosubLines, "GOSUB:\n");

    if (basicPreferences.showXref && !gotoLines.isEmpty ())
      showLines (fullText, gotoLines, "GOTO:\n");

    if (basicPreferences.showSymbols && !symbolLines.isEmpty ())
    {
      if (fullText.charAt (fullText.length () - 2) != '\n')
        fullText.append ("\n");

      fullText.append ("Variables:\n");

      for (String symbol : symbolLines.keySet ())
        fullText.append (String.format ("%6s  %s%n", symbol, symbolLines.get (symbol)));

      boolean headingShown = false;
      for (String key : uniqueSymbols.keySet ())
      {
        List<String> usage = uniqueSymbols.get (key);
        if (usage.size () > 1)
        {
          if (!headingShown)
          {
            headingShown = true;
            fullText.append ("\nNon-unique Variable Names:\n");
          }
          fullText.append (String.format ("%6s  %s%n", key, usage));
        }
      }
    }

    if (basicPreferences.listStrings && stringsLine.size () > 0)
    {
      if (fullText.charAt (fullText.length () - 2) != '\n')
        fullText.append ("\n");
      fullText.append ("Strings:\n");
      for (int i = 0; i < stringsLine.size (); i++)
      {
        fullText.append (
            String.format (" %5s  %s%n", stringsLine.get (i), stringsText.get (i)));
      }
    }

    if (fullText.length () > 0)
      while (fullText.charAt (fullText.length () - 1) == '\n')
        fullText.deleteCharAt (fullText.length () - 1);     // remove trailing newlines

    return fullText.toString ();
  }

  // ---------------------------------------------------------------------------------//
  private void showLines (StringBuilder fullText, Map<Integer, List<Integer>> lines,
      String heading)
  // ---------------------------------------------------------------------------------//
  {
    if (fullText.charAt (fullText.length () - 2) != '\n')
      fullText.append ("\n");
    fullText.append (heading);
    for (Integer line : lines.keySet ())
      fullText.append (String.format (" %5s  %s%n", line, lines.get (line)));
  }

  // ---------------------------------------------------------------------------------//
  private List<String> splitPrint (String line)
  // ---------------------------------------------------------------------------------//
  {
    int first = line.indexOf ("\"") + 1;
    int last = line.indexOf ("\"", first + 1) - 1;

    if (first != 7 || (last - first) <= basicPreferences.wrapPrintAt)
      return null;

    int charsLeft = last - first + 1;

    List<String> lines = new ArrayList<> ();
    String padding = line.substring (0, 7);
    line = line.substring (7);
    String sub;
    while (true)
    {
      if (line.length () >= basicPreferences.wrapPrintAt)
      {
        sub = line.substring (0, basicPreferences.wrapPrintAt);
        line = line.substring (basicPreferences.wrapPrintAt);
      }
      else
      {
        sub = line;
        line = "";
      }

      String subline = padding + sub;
      charsLeft -= basicPreferences.wrapPrintAt;

      if (charsLeft > 0)
        lines.add (subline);
      else
      {
        lines.add (subline + line);
        break;
      }
      padding = "       ";
    }

    return lines;
  }

  // ---------------------------------------------------------------------------------//
  private List<String> splitLine (String line, int wrapLength, char breakChar)
  // ---------------------------------------------------------------------------------//
  {
    int firstSpace = 0;
    while (firstSpace < line.length () && line.charAt (firstSpace) != ' ')
      ++firstSpace;

    List<String> lines = new ArrayList<> ();
    while (line.length () > wrapLength)
    {
      int max = Math.min (wrapLength, line.length () - 1);
      while (max > 0 && line.charAt (max) != breakChar)
        --max;
      if (max == 0)
        break;
      lines.add (line.substring (0, max + 1));
      line = "       ".substring (0, firstSpace + 1) + line.substring (max + 1);
    }

    lines.add (line);
    return lines;
  }

  // ---------------------------------------------------------------------------------//
  private List<String> splitDim (String line)
  // ---------------------------------------------------------------------------------//
  {
    List<String> lines = new ArrayList<> ();

    Pattern p = Pattern.compile ("[A-Z][A-Z0-9]*[$%]?\\([0-9,]*\\)[,:]?");
    Matcher m = p.matcher (line);

    while (m.find ())
      lines.add ("    " + m.group ());

    if (lines.size () > 0)
      lines.set (0, "DIM " + lines.get (0).trim ());

    return lines;
  }

  // ---------------------------------------------------------------------------------//
  private void addSplitLines (List<String> lines, StringBuilder text, int indent)
  // ---------------------------------------------------------------------------------//
  {
    boolean first = true;

    for (String line : lines)
    {
      if (first)
      {
        first = false;
        text.append (line);
      }
      else
        text.append (
            "\n                                           ".substring (0, indent) + line);
    }
  }

  // ---------------------------------------------------------------------------------//
  private int getIndent (StringBuilder fullText)
  // ---------------------------------------------------------------------------------//
  {
    int ptr = fullText.length () - 1;
    int indent = 0;
    while (ptr >= 0 && fullText.charAt (ptr) != '\n')
    {
      --ptr;
      ++indent;
    }
    return indent;
  }

  // ---------------------------------------------------------------------------------//
  private int countChars (StringBuilder text, byte ch)
  // ---------------------------------------------------------------------------------//
  {
    int total = 0;
    for (int i = 0; i < text.length (); i++)
      if (text.charAt (i) == ch)
        total++;
    return total;
  }

  // ---------------------------------------------------------------------------------//
  private String getBase (SourceLine line)
  // ---------------------------------------------------------------------------------//
  {
    boolean isTarget = gotoLines.containsKey (line.lineNumber)
        || gosubLines.containsKey (line.lineNumber);

    if (!basicPreferences.showTargets)
    {
      if (!isTarget && basicPreferences.onlyShowTargetLineNumbers)
        return "      ";
      return String.format (" %5d", line.lineNumber);
    }

    String lineNumberText = String.format ("%5d", line.lineNumber);
    SubLine subline = line.sublines.get (0);
    String c1 = "  ", c2 = "  ";

    if (subline.is (TOKEN_GOSUB) || (subline.is (TOKEN_ON) && subline.has (TOKEN_GOSUB)))
      c1 = "<<";
    else if (subline.is (TOKEN_GOTO)
        || (subline.is (TOKEN_ON) && subline.has (TOKEN_GOTO)))
      c1 = " <";

    if (gotoLines.containsKey (line.lineNumber))
      c2 = "> ";
    if (gosubLines.containsKey (line.lineNumber))
      c2 = ">>";
    if (c1.equals ("  ") && !c2.equals ("  "))
      c1 = "--";
    if (!c1.equals ("  ") && c2.equals ("  "))
      c2 = "--";

    if (!isTarget && basicPreferences.onlyShowTargetLineNumbers)
      lineNumberText = "";

    return String.format ("%s%s %s", c1, c2, lineNumberText);
  }

  // Decide whether the current subline needs to be aligned on its equals sign. If so,
  // and the column hasn't been calculated, read ahead to find the highest position.
  // ---------------------------------------------------------------------------------//
  private int alignEqualsPosition (SubLine subline, int currentAlignPosition)
  // ---------------------------------------------------------------------------------//
  {
    if (subline.assignEqualPos > 0)                   // does the line have an equals sign?
    {
      if (currentAlignPosition == 0)
        currentAlignPosition = findHighest (subline); // examine following sublines
      return currentAlignPosition;
    }
    return 0;                                         // reset it
  }

  // The IF processing is so that any assignment that is being aligned doesn't continue
  // to the next full line (because the indentation has changed).
  // ---------------------------------------------------------------------------------//
  private int findHighest (SubLine startSubline)
  // ---------------------------------------------------------------------------------//
  {
    boolean started = false;
    int highestAssign = startSubline.assignEqualPos;

    fast: for (SourceLine line : sourceLines)
    {
      boolean inIf = false;
      for (SubLine subline : line.sublines)
      {
        if (started)
        {
          // Stop when we come to a line without an equals sign (except for non-split REMs).
          // Lines that start with a REM always break.
          if (subline.assignEqualPos == 0
              // && (splitRem || !subline.is (TOKEN_REM) || subline.isFirst ()))
              && (basicPreferences.splitRem || !subline.isJoinableRem ()))
            break fast; // of champions

          if (subline.assignEqualPos > highestAssign)
            highestAssign = subline.assignEqualPos;
        }
        else if (subline == startSubline)
          started = true;
        else if (subline.is (TOKEN_IF))
          inIf = true;
      }
      if (started && inIf)
        break;
    }
    return highestAssign;
  }

  // ---------------------------------------------------------------------------------//
  private String getHexText ()
  // ---------------------------------------------------------------------------------//
  {
    if (buffer.length < 2)
      return super.getHexDump ();

    StringBuilder pgm = new StringBuilder ();
    if (basicPreferences.showHeader)
      addHeader (pgm);

    int ptr = 0;
    int offset = Utility.unsignedShort (buffer, 0);
    int programLoadAddress = offset - getLineLength (0);

    while (ptr <= endPtr)             // stop at the same place as the source listing
    {
      int length = getLineLength (ptr);
      if (length == 0)
      {
        pgm.append (
            HexFormatter.formatNoHeader (buffer, ptr, 2, programLoadAddress + ptr));
        ptr += 2;
        break;
      }

      if (ptr + length < buffer.length)
        pgm.append (
            HexFormatter.formatNoHeader (buffer, ptr, length, programLoadAddress + ptr)
                + "\n\n");
      ptr += length;
    }

    if (ptr < buffer.length)
    {
      int length = buffer.length - ptr;
      pgm.append ("\n\n");
      pgm.append (
          HexFormatter.formatNoHeader (buffer, ptr, length, programLoadAddress + ptr));
    }

    return pgm.toString ();
  }

  // ---------------------------------------------------------------------------------//
  private void addHeader (StringBuilder pgm)
  // ---------------------------------------------------------------------------------//
  {
    pgm.append ("Name    : " + name + "\n");
    pgm.append (String.format ("Length  : $%04X (%<,d)%n", buffer.length));
    pgm.append (String.format ("Load at : $%04X (%<,d)%n%n", getLoadAddress ()));
  }

  // ---------------------------------------------------------------------------------//
  private int getLoadAddress ()
  // ---------------------------------------------------------------------------------//
  {
    int programLoadAddress = 0;
    if (buffer.length > 1)
    {
      int offset = Utility.unsignedShort (buffer, 0);
      programLoadAddress = offset - getLineLength (0);
    }
    return programLoadAddress;
  }

  // ---------------------------------------------------------------------------------//
  private int getLineLength (int ptr)
  // ---------------------------------------------------------------------------------//
  {
    int offset = Utility.unsignedShort (buffer, ptr);
    if (offset == 0)
      return 0;
    ptr += 4;               // skip offset and line number
    int length = 5;

    while (ptr < buffer.length && buffer[ptr++] != 0)
      length++;

    return length;
  }

  // ---------------------------------------------------------------------------------//
  private void popLoopVariables (Stack<String> loopVariables, SubLine subline)
  // ---------------------------------------------------------------------------------//
  {
    if (subline.nextVariables.length == 0)                    // naked NEXT
    {
      if (loopVariables.size () > 0)
        loopVariables.pop ();
    }
    else
      for (String variable : subline.nextVariables)           // e.g. NEXT X,Y,Z
        while (loopVariables.size () > 0)
          if (sameVariable (variable, loopVariables.pop ()))
            break;
  }

  // ---------------------------------------------------------------------------------//
  private boolean sameVariable (String v1, String v2)
  // ---------------------------------------------------------------------------------//
  {
    if (v1.equals (v2))
      return true;
    if (v1.length () >= 2 && v2.length () >= 2 && v1.charAt (0) == v2.charAt (0)
        && v1.charAt (1) == v2.charAt (1))
      return true;
    return false;
  }

  // ---------------------------------------------------------------------------------//
  private class SourceLine
  // ---------------------------------------------------------------------------------//
  {
    List<SubLine> sublines = new ArrayList<> ();
    int lineNumber;
    int linePtr;
    int length;

    SourceLine (int ptr)
    {
      linePtr = ptr;
      lineNumber = Utility.unsignedShort (buffer, ptr + 2);

      int startPtr = ptr += 4;
      boolean inString = false;           // can toggle
      boolean inRemark = false;           // can only go false -> true
      byte b;
      int stringPtr = 0;

      while (ptr < buffer.length && (b = buffer[ptr++]) != 0)
      {
        if (inRemark)                     // cannot terminate a REM
          continue;

        if (inString)
        {
          if (b == ASCII_QUOTE)           // terminate string
          {
            inString = false;
            String s = new String (buffer, stringPtr - 1, ptr - stringPtr + 1);
            stringsText.add (s);
            stringsLine.add (lineNumber);
          }
          continue;
        }

        switch (b)
        {
          // break IF statements into two sublines (allows for easier line indenting)
          case TOKEN_IF:
            // skip to THEN or GOTO - if not found then it's an error
            while (buffer[ptr] != TOKEN_THEN && buffer[ptr] != TOKEN_GOTO
                && buffer[ptr] != 0)
              ptr++;

            // keep THEN with the IF
            if (buffer[ptr] == TOKEN_THEN)
              ++ptr;

            // create subline from the condition (and THEN if it exists)
            sublines.add (new SubLine (this, startPtr, ptr - startPtr));
            startPtr = ptr;

            break;

          // end of subline, so add it, advance startPtr and continue
          case ASCII_COLON:
            sublines.add (new SubLine (this, startPtr, ptr - startPtr));
            startPtr = ptr;
            break;

          case TOKEN_REM:
            if (ptr != startPtr + 1)      // REM appears mid-line (should follow a colon)
            {
              System.out.println ("mid-line REM token");
              //     System.out.println (HexFormatter.format (buffer, startPtr, 10));
              sublines.add (new SubLine (this, startPtr, (ptr - startPtr) - 1));
              startPtr = ptr - 1;
            }
            else
              inRemark = true;

            break;

          case ASCII_QUOTE:
            inString = true;
            stringPtr = ptr;
            break;
        }
      }

      // add whatever is left
      sublines.add (new SubLine (this, startPtr, ptr - startPtr));
      this.length = ptr - linePtr;
    }
  }

  // ---------------------------------------------------------------------------------//
  private class SubLine
  // ---------------------------------------------------------------------------------//
  {
    SourceLine parent;
    int startPtr;
    int length;
    String[] nextVariables;
    String forVariable = "";
    String onExpression = "";
    int assignEqualPos;               // used for aligning the equals sign

    SubLine (SourceLine parent, int startPtr, int length)
    {
      this.parent = parent;
      this.startPtr = startPtr;
      this.length = length;

      byte firstByte = buffer[startPtr];

      if (isHighBitSet (firstByte))
        doToken (firstByte);
      else if (isDigit (firstByte))
        doDigit ();
      else
        doAlpha ();

      if (is (TOKEN_REM) || is (TOKEN_DATA))
        return;

      int ptr = startPtr;
      length--;
      String var = "";
      boolean inQuote = false;

      while (length-- > 0)
      {
        byte b = buffer[ptr++];

        if (inQuote && b != ASCII_QUOTE)
          continue;

        if (isPossibleVariable (b))
          var += (char) b;
        else
        {
          checkVar (var, b);
          var = "";

          if (b == ASCII_QUOTE)
            inQuote = !inQuote;
        }
      }
      checkVar (var, (byte) 0);
    }

    private void checkVar (String var, byte term)
    {
      if (var.length () == 0)
        return;

      if (term == ASCII_LEFT_BRACKET)
        var += "(";

      if (isLetter ((byte) var.charAt (0)))
      {
        List<Integer> lines = symbolLines.get (var);
        if (lines == null)
        {
          lines = new ArrayList<> ();
          symbolLines.put (var, lines);
        }
        if (lines.size () == 0)
          lines.add (parent.lineNumber);
        else
        {
          int lastLine = lines.get (lines.size () - 1);
          if (lastLine != parent.lineNumber)
            lines.add (parent.lineNumber);
        }
        checkUniqueName (var);
      }
    }

    private void doToken (byte b)
    {
      switch (b)
      {
        case TOKEN_FOR:
          int p = startPtr + 1;
          while (buffer[p] != TOKEN_EQUALS)
            forVariable += (char) buffer[p++];
          break;

        case TOKEN_NEXT:
          if (length == 2)                // no variables
            nextVariables = new String[0];
          else
          {
            String varList = new String (buffer, startPtr + 1, length - 2);
            nextVariables = varList.split (",");
          }
          break;

        case TOKEN_LET:
          recordEqualsPosition ();
          break;

        case TOKEN_GOTO:
          int targetLine = getLineNumber (buffer, startPtr + 1);
          addXref (targetLine, gotoLines);
          break;

        case TOKEN_GOSUB:
          targetLine = getLineNumber (buffer, startPtr + 1);
          addXref (targetLine, gosubLines);
          break;

        case TOKEN_ON:
          p = startPtr + 1;
          int max = startPtr + length - 1;
          while (p < max && buffer[p] != TOKEN_GOTO && buffer[p] != TOKEN_GOSUB)
          {
            if (isHighBitSet (buffer[p]))
            {
              int val = buffer[p] & 0x7F;
              if (val < ApplesoftConstants.tokens.length)
                onExpression += " " + ApplesoftConstants.tokens[val];
            }
            else
              onExpression += (char) (buffer[p]);
            p++;
          }

          switch (buffer[p++])
          {
            case TOKEN_GOSUB:
              for (int destLine : getLineNumbers (buffer, p))
                addXref (destLine, gosubLines);
              break;

            case TOKEN_GOTO:
              for (int destLine : getLineNumbers (buffer, p))
                addXref (destLine, gotoLines);
              break;

            default:
              System.out.println ("GOTO / GOSUB not found");
          }
          break;
      }
    }

    private String checkUniqueName (String symbol)
    {
      int ptr = symbol.length () - 1;
      if (symbol.charAt (ptr) == ASCII_LEFT_BRACKET)      // array
        ptr--;
      if (symbol.charAt (ptr) == ASCII_DOLLAR || symbol.charAt (ptr) == ASCII_PERCENT)
        ptr--;

      String unique =
          (ptr <= 1) ? symbol : symbol.substring (0, 2) + symbol.substring (ptr + 1);

      List<String> usage = uniqueSymbols.get (unique);
      if (usage == null)
      {
        usage = new ArrayList<> ();
        uniqueSymbols.put (unique, usage);
      }

      if (!usage.contains (symbol))
        usage.add (symbol);

      //      System.out.printf ("%8s %s%n", symbol, unique);
      return unique;
    }

    private void doDigit ()
    {
      int targetLine = getLineNumber (buffer, startPtr);
      addXref (targetLine, gotoLines);
    }

    private void doAlpha ()
    {
      recordEqualsPosition ();
    }

    private List<Integer> getLineNumbers (byte[] buffer, int ptr)
    {
      List<Integer> lineNumbers = new ArrayList<> ();
      int start = ptr;

      while (ptr < buffer.length && buffer[ptr] != 0 && buffer[ptr] != ASCII_COLON)
        ptr++;

      String s = new String (buffer, start, ptr - start);

      String[] chunks = s.split (",");

      try
      {
        for (String chunk : chunks)
          lineNumbers.add (Integer.parseInt (chunk));
      }
      catch (NumberFormatException e)
      {
        System.out.printf ("NFE: %s%n", s);
      }

      return lineNumbers;
    }

    private int getLineNumber (byte[] buffer, int ptr)
    {
      int lineNumber = 0;
      while (ptr < buffer.length)
      {
        int b = (buffer[ptr++] & 0xFF) - 0x30;
        if (b < 0 || b > 9)
          break;
        lineNumber = lineNumber * 10 + b;
      }
      return lineNumber;
    }

    private void addXref (int targetLine, Map<Integer, List<Integer>> map)
    {
      List<Integer> lines = map.get (targetLine);
      if (lines == null)
      {
        lines = new ArrayList<> ();
        map.put (targetLine, lines);
      }
      lines.add (parent.lineNumber);
    }

    private boolean isImpliedGoto ()
    {
      byte b = buffer[startPtr];
      if (isHighBitSet (b))
        return false;
      return (isDigit (b));
    }

    // Record the position of the equals sign so it can be aligned with adjacent lines.
    private void recordEqualsPosition ()
    {
      int p = startPtr + 1;
      int max = startPtr + length;
      while (buffer[p] != TOKEN_EQUALS && p < max)
        p++;
      if (buffer[p] == TOKEN_EQUALS)
        assignEqualPos = toString ().indexOf ('=');           // use expanded line
    }

    private boolean isJoinableRem ()
    {
      return is (TOKEN_REM) && !isFirst ();
    }

    boolean isFirst ()
    {
      return (parent.linePtr + 4) == startPtr;
    }

    boolean is (byte token)
    {
      return buffer[startPtr] == token;
    }

    boolean has (byte token)
    {
      int ptr = startPtr + 1;
      int max = startPtr + length;
      while (ptr < max)
      {
        if (buffer[ptr++] == token)
          return true;
      }
      return false;
    }

    boolean isEmpty ()
    {
      return length == 1 && buffer[startPtr] == 0;
    }

    boolean containsToken ()
    {
      // ignore first byte, check the rest for tokens
      for (int p = startPtr + 1, max = startPtr + length; p < max; p++)
        if (isHighBitSet (buffer[p]))
          return true;

      return false;
    }

    boolean containsControlChars ()
    {
      for (int p = startPtr + 1, max = startPtr + length; p < max; p++)
      {
        int c = buffer[p] & 0xFF;
        if (c == 0)
          break;

        if (c < 32)
          return true;
      }

      return false;
    }

    void addFormattedRem (StringBuilder text)
    {
      int ptr = startPtr + 1;
      int max = startPtr + length - 2;

      while (ptr <= max)
      {
        int c = buffer[ptr] & 0xFF;
        //        System.out.printf ("%02X  %s%n", c, (char) c);
        if (c == 0x08 && text.length () > 0)
          text.deleteCharAt (text.length () - 1);
        else if (c == 0x0D)
          text.append ("\n");
        else
          text.append ((char) c);
        ptr++;
      }
    }

    public int getAddress ()
    {
      return getLoadAddress () + startPtr;
    }

    public String getAlignedText (int alignPosition)
    {
      StringBuilder line = toStringBuilder ();

      while (alignPosition-- > assignEqualPos)
        line.insert (assignEqualPos, ' ');

      return line.toString ();
    }

    // A REM statement might conceal an assembler routine
    public String[] getAssembler ()
    {
      byte[] buffer2 = new byte[length - 1];
      System.arraycopy (buffer, startPtr + 1, buffer2, 0, buffer2.length);
      AssemblerProgram program =
          new AssemblerProgram ("REM assembler", buffer2, getAddress () + 1);
      return program.getAssembler ().split ("\n");
    }

    @Override
    public String toString ()
    {
      return toStringBuilder ().toString ();
    }

    public StringBuilder toStringBuilder ()
    {
      StringBuilder line = new StringBuilder ();

      // All sublines end with 0 or : except IF lines that are split into two
      int max = startPtr + length - 1;
      if (buffer[max] == 0)
        --max;

      if (isImpliedGoto () && !basicPreferences.showThen)
        line.append ("GOTO ");

      for (int p = startPtr; p <= max; p++)
      {
        byte b = buffer[p];
        if (isHighBitSet (b))
        {
          if (line.length () > 0 && line.charAt (line.length () - 1) != ' ')
            line.append (' ');
          int val = b & 0x7F;
          if (val < ApplesoftConstants.tokens.length)
          {
            if (b != TOKEN_THEN || basicPreferences.showThen)
              line.append (ApplesoftConstants.tokens[val]);
          }
        }
        else if (isControlCharacter (b))
          line.append (basicPreferences.showCaret ? "^" + (char) (b + 64) : "");
        else
          line.append ((char) b);
      }

      return line;
    }
  }
}