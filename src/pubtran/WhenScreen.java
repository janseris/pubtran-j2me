package pubtran;

import com.gtrxac.discord.App;

import java.util.Date;
import javax.microedition.lcdui.*;

/** "Kdy" - date/time, departure vs arrival, and the search filters. */
public class WhenScreen extends Form implements CommandListener, ItemStateListener, ItemCommandListener {
    private static final Command OK_COMMAND = new Command("OK", Command.OK, 1);
    private static final Command NOW_COMMAND = new Command("Teď", Command.SCREEN, 2);
    private static final Command BACK_COMMAND = new Command("Zpět", Command.BACK, 3);
    /** OK as an item command too - on S80 a focused field's CBA shows only its own commands. */
    private static final Command OK_ITEM_COMMAND = new Command("OK", Command.ITEM, 1);

    private static final String[] MODES = {"Vlak", "Bus", "Tramvaj", "Trolejbus", "Metro", "Lanovka", "Loď"};

    private final StartScreen back;
    private final DateField when = new DateField("Datum a čas:", DateField.DATE_TIME);
    private final ChoiceGroup now = new ChoiceGroup(null, Choice.MULTIPLE);
    private final ChoiceGroup direction = new ChoiceGroup("Čas je:", Choice.EXCLUSIVE);
    private final ChoiceGroup options = new ChoiceGroup("Možnosti:", Choice.MULTIPLE);
    private final ChoiceGroup modes = new ChoiceGroup("Doprava:", Choice.MULTIPLE);

    public WhenScreen(StartScreen back) {
        super("Kdy");
        this.back = back;
        SearchState s = SearchState.current;

        now.append("Aktuální čas (teď)", null);
        now.setSelectedIndex(0, s.useNow);

        if (s.useNow) s.setTime(System.currentTimeMillis());
        when.setDate(new Date(s.getTimeMillis()));

        direction.append("Odjezd", null);
        direction.append("Příjezd", null);
        direction.setSelectedIndex(s.isDeparture ? 0 : 1, true);

        options.append("Jen přímé spoje", null);
        options.append("Nízkopodlažní", null);
        options.setSelectedIndex(0, s.onlyDirect);
        options.setSelectedIndex(1, s.lowFloor);

        boolean[] m = {s.train, s.bus, s.tram, s.trolley, s.metro, s.cable, s.ferry};
        for (int i = 0; i < MODES.length; i++) {
            modes.append(MODES[i], null);
            modes.setSelectedIndex(i, m[i]);
        }

        Item[] items = {now, when, direction, options, modes};
        for (int i = 0; i < items.length; i++) {
            items[i].addCommand(OK_ITEM_COMMAND);
            items[i].setItemCommandListener(this);
            append(items[i]);
        }

        addCommand(OK_COMMAND);
        addCommand(NOW_COMMAND);
        addCommand(BACK_COMMAND);
        setCommandListener(this);
        setItemStateListener(this);
    }

    /** Editing the date/time means "not now". */
    public void itemStateChanged(Item item) {
        if (item == when) now.setSelectedIndex(0, false);
    }

    private void save() {
        SearchState s = SearchState.current;
        s.useNow = now.isSelected(0);
        Date d = when.getDate();
        if (!s.useNow && d != null) s.setTime(d.getTime());
        s.isDeparture = direction.getSelectedIndex() == 0;
        s.onlyDirect = options.isSelected(0);
        s.lowFloor = options.isSelected(1);
        s.train = modes.isSelected(0);
        s.bus = modes.isSelected(1);
        s.tram = modes.isSelected(2);
        s.trolley = modes.isSelected(3);
        s.metro = modes.isSelected(4);
        s.cable = modes.isSelected(5);
        s.ferry = modes.isSelected(6);
    }

    public void commandAction(Command c, Item item) {
        if (c == OK_ITEM_COMMAND) commandAction(OK_COMMAND, this);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == OK_COMMAND) {
            save();
        }
        else if (c == NOW_COMMAND) {
            save();
            SearchState.current.useNow = true;
        }
        back.refresh();
        App.disp.setCurrent(back);
    }
}
