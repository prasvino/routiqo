import { useEffect, useState } from 'react';
import {
  AppState,
  Alert,
  FlatList,
  Image,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  schedulePlans,
  departureLabel,
  recurrenceLabel,
  categories,
  dayLabels,
  destinations,
  localDate,
  searchDestinations,
  toggleSaved,
  upsertPlan,
  type Category,
  type Destination,
  type JourneyPlan,
} from '@routiqo/shared';
import { tokens } from '@routiqo/design-tokens';
import { useMobilePlanning } from '../../storage/planning';
import { NativePlanningBackup } from './planning-backup';
import pondicherry from '../../../assets/pondicherry.jpg';
import heritage from '../../../assets/heritage.jpg';
import hills from '../../../assets/hills.jpg';
import coast from '../../../assets/coast.jpg';
const photos: Record<string, number> = {
  '/images/pondicherry.jpg': pondicherry,
  '/images/heritage.jpg': heritage,
  '/images/hills.jpg': hills,
  '/images/coast.jpg': coast,
};
const c = tokens.colors;
export function DiscoveryScreen({
  section,
}: {
  section: 'Home' | 'Explore' | 'Trips' | 'Profile';
}) {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const refresh = () => setNow(new Date());
    const timer = setInterval(refresh, 60000);
    const subscription = AppState.addEventListener('change', (status) => {
      if (status === 'active') refresh();
    });
    return () => {
      clearInterval(timer);
      subscription.remove();
    };
  }, []);
  const { state, ready, error, update, clear } = useMobilePlanning();
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState<Category>('All');
  const [detail, setDetail] = useState<Destination | null>(null);
  const [form, setForm] = useState<JourneyPlan | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  function plan(destination = '') {
    setForm({
      id:
        globalThis.crypto?.randomUUID?.() ??
        String(Date.now()) + '-' + Math.random().toString(36).slice(2),
      kind: 'trip',
      origin: 'Chennai',
      destination,
      date: localDate(),
      time: '08:00',
      days: [1, 2, 3, 4, 5],
      notes: '',
      createdAt: new Date().toISOString(),
    });
    setFormError('');
  }
  function change(patch: Partial<JourneyPlan>) {
    setForm((current) => (current ? { ...current, ...patch } : null));
  }
  async function save() {
    if (!form) return;
    setSaving(true);
    try {
      await update((current) =>
        upsertPlan(current, {
          ...form,
          origin: form.origin.trim(),
          destination: form.destination.trim(),
          days: form.kind === 'trip' ? [] : form.days,
        }),
      );
      setForm(null);
    } catch (e) {
      setFormError(e instanceof Error ? e.message : 'Check your plan details.');
    } finally {
      setSaving(false);
    }
  }
  function saved(id: string) {
    void update((current) => toggleSaved(current, id)).catch(() => {
      /* provider displays storage error */
    });
  }
  function remove(plan: JourneyPlan) {
    Alert.alert('Remove this plan?', plan.origin + ' → ' + plan.destination, [
      { text: 'Keep', style: 'cancel' },
      {
        text: 'Remove',
        style: 'destructive',
        onPress: () => {
          void update((current) => ({
            ...current,
            plans: current.plans.filter((p) => p.id !== plan.id),
          })).catch(() => {});
        },
      },
    ]);
  }
  const scheduled = schedulePlans(state.plans, now);
  const next = scheduled.find((item) => item.departure);
  const places =
    section === 'Profile'
      ? destinations.filter((place) => state.saved.includes(place.id))
      : searchDestinations(query, category);
  return (
    <SafeAreaView edges={['bottom']} style={s.safe}>
      <FlatList
        data={section === 'Trips' ? [] : places}
        keyExtractor={(item) => item.id}
        contentContainerStyle={s.list}
        ListHeaderComponent={
          <>
            <Text style={s.eyebrow}>
              {section === 'Home' ? 'CHENNAI & BEYOND' : section.toUpperCase()}
            </Text>
            <Text style={s.title}>
              {section === 'Home'
                ? 'Where shall we go?'
                : section === 'Trips'
                  ? 'Journeys ahead.'
                  : section === 'Profile'
                    ? 'Your places. Your pace.'
                    : 'A little beyond the usual.'}
            </Text>
            <Text style={s.subtitle}>
              {section === 'Profile'
                ? 'You’re exploring without an account. Plans stay on this device.'
                : 'Your everyday route. Your next little escape.'}
            </Text>
            {error ? (
              <Text accessibilityRole="alert" style={s.error}>
                {error}
              </Text>
            ) : null}
            {section !== 'Profile' && (
              <Pressable style={s.primary} accessibilityRole="button" onPress={() => plan()}>
                <Text style={s.primaryText}>Plan a journey →</Text>
              </Pressable>
            )}
            {section === 'Home' && ready && next && (
              <View style={s.plan}>
                <Text style={s.eyebrow}>NEXT PLANNED DEPARTURE</Text>
                <Text style={s.cardTitle}>
                  {next.plan.origin} → {next.plan.destination}
                </Text>
                <Text style={s.subtitle}>
                  {departureLabel(next.departure, now)} · {recurrenceLabel(next.plan)}
                </Text>
                <Text style={s.subtitle}>Device local time · No reminder</Text>
                <Pressable
                  style={s.secondary}
                  onPress={() => {
                    setForm(next.plan);
                    setFormError('');
                  }}
                  accessibilityRole="button"
                  accessibilityLabel={'Edit next plan to ' + next.plan.destination}
                >
                  <Text>Edit plan</Text>
                </Pressable>
              </View>
            )}
            {(section === 'Home' || section === 'Explore') && (
              <>
                <TextInput
                  style={s.input}
                  accessibilityLabel="Search destinations"
                  placeholder="Find a place, a hill town, the coast…"
                  value={query}
                  onChangeText={setQuery}
                />
                <View style={s.chips}>
                  {categories.map((item) => (
                    <Pressable
                      key={item}
                      style={[s.chip, category === item && s.selected]}
                      accessibilityRole="button"
                      accessibilityState={{ selected: category === item }}
                      onPress={() => setCategory(item)}
                    >
                      <Text style={category === item ? s.selectedText : s.chipText}>{item}</Text>
                    </Pressable>
                  ))}
                </View>
              </>
            )}
            {section === 'Trips' && (
              <>
                <Text style={s.notice}>
                  Device local time. No reminders or cloud sync. Earlier plans follow upcoming
                  departures.
                </Text>
                {!ready ? (
                  <Text>Loading plans…</Text>
                ) : state.plans.length ? (
                  scheduled.map(({ plan: item, departure }) => (
                    <View key={item.id} style={s.plan}>
                      <Text style={s.eyebrow}>
                        {item.kind === 'commute' ? 'DAILY COMMUTE' : 'TRIP'}
                      </Text>
                      <Text style={s.cardTitle}>
                        {item.origin} → {item.destination}
                      </Text>
                      <Text style={s.subtitle}>
                        {departureLabel(departure, now)} · {recurrenceLabel(item)}
                      </Text>
                      {item.notes ? <Text style={s.subtitle}>{item.notes}</Text> : null}
                      <View style={s.chips}>
                        <Pressable
                          style={s.chip}
                          onPress={() => {
                            setForm(item);
                            setFormError('');
                          }}
                          accessibilityRole="button"
                          accessibilityLabel={'Edit plan to ' + item.destination}
                        >
                          <Text>Edit</Text>
                        </Pressable>
                        <Pressable
                          style={s.chip}
                          onPress={() => remove(item)}
                          accessibilityRole="button"
                          accessibilityLabel={'Remove plan to ' + item.destination}
                        >
                          <Text>Remove</Text>
                        </Pressable>
                      </View>
                    </View>
                  ))
                ) : (
                  <Text style={s.empty}>
                    Your next journey starts here. Save a trip or a recurring commute.
                  </Text>
                )}
              </>
            )}
            {section === 'Profile' && (
              <>
                <Text style={s.notice}>
                  No GPS collected. No live position shared. Clear your plans and saved places at
                  any time.
                </Text>
                <Pressable
                  style={s.secondary}
                  accessibilityRole="button"
                  onPress={() =>
                    Alert.alert(
                      'Clear local data?',
                      'All plans and saved places on this device will be removed.',
                      [
                        { text: 'Keep', style: 'cancel' },
                        {
                          text: 'Clear',
                          style: 'destructive',
                          onPress: () => {
                            void clear();
                          },
                        },
                      ],
                    )
                  }
                >
                  <Text>Clear local planning data</Text>
                </Pressable>
                <NativePlanningBackup />
                <Text style={s.sectionTitle}>Saved places</Text>
              </>
            )}
          </>
        }
        renderItem={({ item }) => (
          <View style={s.card}>
            <Pressable
              onPress={() => setDetail(item)}
              accessibilityRole="button"
              accessibilityLabel={'Explore ' + item.name}
            >
              <Image
                source={photos[item.image]}
                style={s.image}
                accessibilityLabel={item.imageAlt}
              />
              <Text style={s.cardTitle}>{item.name} ↗</Text>
              <Text style={s.subtitle}>{item.description}</Text>
            </Pressable>
            <Pressable
              style={s.save}
              onPress={() => saved(item.id)}
              disabled={!ready}
              accessibilityRole="button"
              accessibilityLabel={(state.saved.includes(item.id) ? 'Unsave ' : 'Save ') + item.name}
            >
              <Text>{state.saved.includes(item.id) ? '✓ Saved' : '＋ Save place'}</Text>
            </Pressable>
          </View>
        )}
        ListEmptyComponent={
          section === 'Trips' ? null : (
            <Text style={s.empty}>
              {section === 'Profile'
                ? 'Save a place while exploring and find it here.'
                : 'No destinations found. Try a different search.'}
            </Text>
          )
        }
        ListFooterComponent={<Text style={s.footer}>Made for the journey. Private by design.</Text>}
      />
      <Modal
        visible={detail !== null}
        animationType="slide"
        onRequestClose={() => setDetail(null)}
        presentationStyle="pageSheet"
      >
        {detail && (
          <SafeAreaView style={s.safe}>
            <ScrollView contentContainerStyle={s.list}>
              <Pressable
                style={s.secondary}
                onPress={() => setDetail(null)}
                accessibilityRole="button"
              >
                <Text>Close</Text>
              </Pressable>
              <Image source={photos[detail.image]} style={s.image} />
              <Text style={s.title}>{detail.name}</Text>
              <Text style={s.subtitle}>{detail.detail}</Text>
              <Text style={s.notice}>
                Inspiration only. Check local access, road conditions and weather before travel.
              </Text>
              <Pressable
                style={s.primary}
                accessibilityRole="button"
                onPress={() => {
                  plan(detail.name);
                  setDetail(null);
                }}
              >
                <Text style={s.primaryText}>Plan a journey →</Text>
              </Pressable>
            </ScrollView>
          </SafeAreaView>
        )}
      </Modal>
      <Modal
        visible={form !== null}
        animationType="slide"
        presentationStyle="pageSheet"
        onRequestClose={() => setForm(null)}
      >
        {form && (
          <SafeAreaView style={s.safe}>
            <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={s.list}>
              <Pressable
                style={s.secondary}
                onPress={() => setForm(null)}
                accessibilityRole="button"
              >
                <Text>Cancel</Text>
              </Pressable>
              <Text style={s.title}>Make it your journey.</Text>
              <View style={s.chips}>
                {(['trip', 'commute'] as const).map((kind) => (
                  <Pressable
                    key={kind}
                    style={[s.chip, form.kind === kind && s.selected]}
                    onPress={() => change({ kind })}
                    accessibilityRole="button"
                    accessibilityState={{ selected: form.kind === kind }}
                  >
                    <Text style={form.kind === kind ? s.selectedText : s.chipText}>
                      {kind === 'trip' ? 'Trip / travel' : 'Daily commute'}
                    </Text>
                  </Pressable>
                ))}
              </View>
              {(['origin', 'destination', 'date', 'time', 'notes'] as const).map((field) => (
                <View key={field}>
                  <Text style={s.label}>
                    {
                      {
                        origin: 'Starting from',
                        destination: 'Going to',
                        date: 'Departure date (YYYY-MM-DD)',
                        time: 'Time (HH:MM)',
                        notes: 'Notes · optional',
                      }[field]
                    }
                  </Text>
                  <TextInput
                    accessibilityLabel={field}
                    value={form[field]}
                    onChangeText={(value) => change({ [field]: value })}
                    style={s.input}
                    maxLength={field === 'notes' ? 500 : 100}
                    autoCapitalize="sentences"
                  />
                </View>
              ))}
              {form.kind === 'commute' && (
                <>
                  <Text style={s.label}>Repeat on</Text>
                  <View style={s.chips}>
                    {dayLabels.map((day, index) => (
                      <Pressable
                        key={day}
                        style={[s.chip, form.days.includes(index) && s.selected]}
                        onPress={() =>
                          change({
                            days: form.days.includes(index)
                              ? form.days.filter((d) => d !== index)
                              : [...form.days, index].sort(),
                          })
                        }
                        accessibilityRole="button"
                        accessibilityState={{ selected: form.days.includes(index) }}
                      >
                        <Text style={form.days.includes(index) ? s.selectedText : s.chipText}>
                          {day}
                        </Text>
                      </Pressable>
                    ))}
                  </View>
                </>
              )}
              <Text style={s.notice}>
                Saved on this device. This is a plan, not a live journey or reminder.
              </Text>
              {formError ? (
                <Text accessibilityRole="alert" style={s.error}>
                  {formError}
                </Text>
              ) : null}
              <Pressable
                style={s.primary}
                disabled={saving || !ready}
                onPress={() => {
                  void save();
                }}
                accessibilityRole="button"
              >
                <Text style={s.primaryText}>{saving ? 'Saving…' : 'Save journey plan'}</Text>
              </Pressable>
            </ScrollView>
          </SafeAreaView>
        )}
      </Modal>
    </SafeAreaView>
  );
}
const s = StyleSheet.create({
  safe: { flex: 1, backgroundColor: c.canvas },
  list: { padding: 22, paddingBottom: 40 },
  eyebrow: { fontSize: 10, letterSpacing: 2, color: c.muted, marginBottom: 10 },
  title: {
    fontSize: 34,
    color: c.ink,
    fontWeight: '500',
    letterSpacing: -1,
    lineHeight: 41,
    marginBottom: 12,
  },
  subtitle: { fontSize: 14, lineHeight: 22, color: c.muted, marginBottom: 14 },
  primary: {
    minHeight: 50,
    backgroundColor: c.ink,
    borderRadius: 10,
    padding: 16,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 20,
  },
  primaryText: { color: '#fff', fontSize: 14, fontWeight: '600' },
  secondary: {
    minHeight: 48,
    borderColor: c.line,
    borderWidth: 1,
    borderRadius: 10,
    padding: 14,
    alignItems: 'center',
    marginBottom: 18,
  },
  input: {
    borderColor: c.line,
    borderWidth: 1,
    borderRadius: 9,
    padding: 14,
    fontSize: 16,
    backgroundColor: '#fff',
    color: c.ink,
    marginBottom: 16,
    minHeight: 50,
  },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 22 },
  chip: {
    borderColor: c.line,
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 14,
    minHeight: 46,
    justifyContent: 'center',
  },
  chipText: { color: c.ink, fontSize: 12 },
  selected: { backgroundColor: c.ink },
  selectedText: { color: '#fff', fontSize: 12 },
  card: { marginBottom: 28 },
  image: { height: 210, width: '100%', borderRadius: 14, marginBottom: 13, resizeMode: 'cover' },
  cardTitle: { fontSize: 23, color: c.ink, fontWeight: '500', marginBottom: 8 },
  save: {
    alignSelf: 'flex-start',
    minHeight: 44,
    paddingHorizontal: 12,
    borderRadius: 8,
    backgroundColor: c.soft,
    justifyContent: 'center',
  },
  footer: { fontSize: 12, color: c.muted, textAlign: 'center', marginTop: 25 },
  empty: { fontSize: 15, lineHeight: 24, color: c.muted, marginVertical: 25 },
  notice: {
    fontSize: 12,
    lineHeight: 20,
    padding: 16,
    backgroundColor: c.soft,
    borderRadius: 10,
    color: c.muted,
    marginBottom: 22,
  },
  error: { color: c.danger, fontSize: 13, lineHeight: 20, padding: 10 },
  plan: { borderBottomWidth: 1, borderBottomColor: c.line, paddingVertical: 18 },
  label: { fontSize: 12, fontWeight: '600', color: c.ink, marginBottom: 8 },
  sectionTitle: { fontSize: 25, fontWeight: '500', color: c.ink, marginVertical: 20 },
});
