import * as Serial from '@omeraydin00/expo-serial-port';
import { reloadAppAsync } from 'expo';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useRef, useState } from 'react';
import {
  FlatList,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

const BAUD_RATES = [9600, 19200, 38400, 57600, 115200];
const FRAMINGS: { label: string; value: Serial.Framing }[] = [
  { label: 'raw', value: { type: 'raw' } },
  { label: 'line', value: { type: 'delimiter', bytes: [13, 10] } },
  { label: 'idle 20 ms', value: { type: 'idle', ms: 20 } },
  { label: '8 bytes', value: { type: 'length', size: 8 } },
];

const hex = (bytes: Uint8Array) =>
  Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join(' ');
const label = (p: Serial.PortInfo) =>
  p.kind === 'uart'
    ? `${p.path}${p.readable && p.writable ? '' : ' (no access)'}`
    : `USB ${p.driver} ${p.vendorId.toString(16)}:${p.productId.toString(16)}${p.port ? ` #${p.port}` : ''}${p.hasPermission ? '' : ' (no permission)'}`;

export default function App() {
  const [ports, setPorts] = useState<Serial.PortInfo[]>([]);
  const [selected, setSelected] = useState<Serial.PortInfo | null>(null);
  const [baudRate, setBaudRate] = useState(115200);
  const [framing, setFraming] = useState(0);
  const [useSu, setUseSu] = useState(false);
  const [port, setPort] = useState<Serial.SerialPort | null>(null);
  const [text, setText] = useState('PING');
  const [log, setLog] = useState<string[]>([]);
  const received = useRef<number[]>([]);

  const add = (line: string) =>
    setLog((l) => [`${new Date().toISOString().slice(11, 23)}  ${line}`, ...l].slice(0, 200));

  const refresh = async () => {
    const list = await Serial.list();
    setPorts(list);
    add(`found ${list.length} port(s)`);
  };

  useEffect(() => {
    refresh();
  }, []);

  const run = async (what: string, fn: () => Promise<void>) => {
    try {
      await fn();
    } catch (e: any) {
      add(`${what} failed: ${e.code ?? ''} ${e.message}`);
    }
  };

  const open = () =>
    run('open', async () => {
      let target = selected;
      if (!target) return;
      if (target.kind === 'usb' && !target.hasPermission) {
        const granted = await Serial.requestUsbPermission(target.deviceId);
        add(`USB permission ${granted ? 'granted' : 'denied'}`);
        if (!granted) return;
        target = { ...target, hasPermission: true };
        setSelected(target);
        setPorts((list) => list.map((p) => (p === selected ? target! : p)));
      }
      const p = await Serial.open(target, {
        baudRate,
        framing: FRAMINGS[framing].value,
        useSu: target.kind === 'uart' ? useSu : false,
      });
      p.onData((bytes) => {
        received.current.push(...bytes);
        add(`rx ${bytes.length}: ${hex(bytes)}`);
      });
      p.onError((e) => {
        add(`error ${e.code}: ${e.message}`);
        setPort(null);
      });
      setPort(p);
      add(`opened ${label(target)} at ${baudRate}, ${FRAMINGS[framing].label}`);
    });

  const close = () =>
    run('close', async () => {
      await port?.close();
      setPort(null);
      add('closed');
    });

  const send = () =>
    run('write', async () => {
      await port?.write(`${text}\r\n`);
      add(`tx: ${text}\\r\\n`);
    });

  // With TX wired to RX, everything written comes back.
  const loopback = () =>
    run('loopback', async () => {
      if (!port) return;
      const out = Uint8Array.from({ length: 256 }, (_, i) => i);
      received.current = [];
      const started = Date.now();
      await port.write(out);
      while (received.current.length < out.length && Date.now() - started < 3000) {
        await new Promise((r) => setTimeout(r, 20));
      }
      const back = received.current;
      const same = back.length === out.length && back.every((b, i) => b === out[i]);
      add(
        `loopback ${same ? 'OK' : 'FAILED'}: ${back.length}/${out.length} bytes in ${Date.now() - started} ms`
      );
    });

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar style="dark" />
      <View style={styles.row}>
        <Text style={styles.title}>Ports</Text>
        <Button title="Refresh" onPress={refresh} />
      </View>
      <ScrollView style={styles.ports}>
        {ports.map((p) => (
          <Pressable
            key={label(p)}
            onPress={() => setSelected(p)}
            style={[styles.port, selected === p && styles.selected]}>
            <Text>{label(p)}</Text>
          </Pressable>
        ))}
      </ScrollView>

      <View style={styles.row}>
        {BAUD_RATES.map((b) => (
          <Chip key={b} title={String(b)} active={b === baudRate} onPress={() => setBaudRate(b)} />
        ))}
      </View>
      <View style={styles.row}>
        {FRAMINGS.map((f, i) => (
          <Chip
            key={f.label}
            title={f.label}
            active={i === framing}
            onPress={() => setFraming(i)}
          />
        ))}
      </View>
      <View style={styles.row}>
        <Text>useSu (UART)</Text>
        <Switch value={useSu} onValueChange={setUseSu} />
        {port ? (
          <Button title="Close" onPress={close} />
        ) : (
          <Button title="Open" onPress={open} disabled={!selected} />
        )}
        {/* Reloads the JS bundle with the port left open; the module should close it. */}
        <Button title="Reload JS" onPress={() => reloadAppAsync('reload test')} />
      </View>
      <View style={styles.row}>
        <TextInput style={styles.input} value={text} onChangeText={setText} autoCapitalize="none" />
        <Button title="Send" onPress={send} disabled={!port} />
        <Button title="Loopback" onPress={loopback} disabled={!port} />
      </View>

      <FlatList
        style={styles.log}
        data={log}
        keyExtractor={(l, i) => `${i}${l}`}
        renderItem={({ item }) => <Text style={styles.mono}>{item}</Text>}
      />
    </SafeAreaView>
  );
}

function Button({
  title,
  onPress,
  disabled,
}: {
  title: string;
  onPress: () => void;
  disabled?: boolean;
}) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={[styles.button, disabled && { opacity: 0.4 }]}>
      <Text style={styles.buttonText}>{title}</Text>
    </Pressable>
  );
}

function Chip({ title, active, onPress }: { title: string; active: boolean; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} style={[styles.chip, active && styles.chipActive]}>
      <Text style={active ? styles.buttonText : undefined}>{title}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, padding: 12, gap: 8, backgroundColor: '#fff' },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8, flexWrap: 'wrap' },
  title: { fontSize: 18, fontWeight: '600', flex: 1 },
  ports: { maxHeight: 160, borderWidth: 1, borderColor: '#ddd', borderRadius: 6 },
  port: { padding: 10, borderBottomWidth: 1, borderBottomColor: '#eee' },
  selected: { backgroundColor: '#e8f0fe' },
  chip: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#bbb',
  },
  chipActive: { backgroundColor: '#1a73e8', borderColor: '#1a73e8' },
  button: {
    backgroundColor: '#1a73e8',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 6,
  },
  buttonText: { color: '#fff', fontWeight: '600' },
  input: {
    flex: 1,
    minWidth: 120,
    borderWidth: 1,
    borderColor: '#bbb',
    borderRadius: 6,
    padding: 8,
  },
  log: { flex: 1, borderTopWidth: 1, borderTopColor: '#ddd' },
  mono: { fontFamily: 'monospace', fontSize: 12, paddingVertical: 2 },
});
