import SwiftUI
import Contacts
import ContactsUI

/// People == the system Contacts (iOS). A person of the app is a CNContact plus what only the app knows (its id in
/// the meetings, voice, note): `Person.contact.key` is the contact identifier, and `Person.emails` a cache of the
/// contact's addresses, refreshed by `PeopleStore.syncContacts()` (launch, foreground, after "Edit in Contacts").
/// Without access, no person can be created: a speaker gets a plain name only (no person, e-mail or voice).
/// iOS 18 limited access: contacts the app created stay readable; the others keep the addresses cached at pick time.
@MainActor enum OSContacts {
    enum Access { case full, limited, denied, notDetermined }
    static let store = CNContactStore()

    static var access: Access {
        if Dev.env["VOX_CONTACTS_DENIED"] != nil { return .denied }   // DEV: the blocked path without touching Settings
        switch CNContactStore.authorizationStatus(for: .contacts) {
        case .authorized: return .full
        case .notDetermined: return .notDetermined
        case .denied, .restricted: return .denied
        default: return .limited   // .limited (iOS 18+)
        }
    }
    static var canWrite: Bool { access == .full || access == .limited }

    /// Asks once (the system prompt); afterwards only Settings can change it.
    @discardableResult static func requestIfNeeded() async -> Access {
        if access == .notDetermined { _ = try? await store.requestAccess(for: .contacts) }
        StatusLog.add("contacts: access \(access)")
        return access
    }
    static func openSettings() { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }

    private static let keys: [CNKeyDescriptor] = [CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
                                                  CNContactEmailAddressesKey as CNKeyDescriptor, CNContactOrganizationNameKey as CNKeyDescriptor]

    /// A new contact named `name` (given/family split on the last space for Latin names; Han names go whole in the
    /// given name, as Contacts shows them) with `emails`. Returns its identifier, nil when it could not be saved.
    static func create(_ name: String, emails: [String] = []) -> String? {
        guard canWrite else { return nil }
        let c = CNMutableContact(), n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let sp = n.lastIndex(of: " "), n.unicodeScalars.allSatisfy({ $0.isASCII || $0.properties.isAlphabetic && !$0.properties.isIdeographic }) {
            c.givenName = String(n[..<sp]); c.familyName = String(n[n.index(after: sp)...])
        } else { c.givenName = n }
        c.emailAddresses = emails.map { CNLabeledValue(label: CNLabelOther, value: $0 as NSString) }
        let r = CNSaveRequest(); r.add(c, toContainerWithIdentifier: nil)
        do { try store.execute(r) } catch { StatusLog.add("contacts: could not create \(n): \(error)"); return nil }
        StatusLog.add("contacts: created \(n)")
        return c.identifier
    }

    /// The contact, nil when it is gone (or, with limited access, not shared with the app: `missing` tells which).
    static func fetch(_ key: String) -> (contact: CNContact?, missing: Bool) {
        guard canWrite, !key.hasPrefix("dev-") else { return (nil, false) }
        do { return (try store.unifiedContact(withIdentifier: key, keysToFetch: keys), false) }
        catch let e as CNError where e.code == .recordDoesNotExist { return (nil, access == .full) }
        catch { return (nil, false) }
    }
    static func emails(_ c: CNContact) -> [String] {
        c.emailAddresses.map { EmailRules.normalize($0.value as String) }.filter(EmailRules.isValid)
    }
    static func name(_ c: CNContact) -> String {
        let n = (CNContactFormatter.string(from: c, style: .fullName) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return n.isEmpty ? c.organizationName : n
    }

    /// Adds the addresses the contact lacks (the mail flow's "add address", the migration of typed addresses).
    static func addEmails(_ key: String, _ add: [String]) {
        guard let c = fetch(key).contact else { return }
        let have = Set(emails(c).map(EmailRules.key))
        let new = add.filter { EmailRules.isValid($0) && !have.contains(EmailRules.key($0)) }
        guard !new.isEmpty, let m = c.mutableCopy() as? CNMutableContact else { return }
        m.emailAddresses += new.map { CNLabeledValue(label: CNLabelOther, value: EmailRules.normalize($0) as NSString) }
        let r = CNSaveRequest(); r.update(m)
        do { try store.execute(r) } catch { StatusLog.add("contacts: could not add an address: \(error)") }
    }

    /// DEV cleanup: deletes the readable contacts whose name starts with `prefix` (test contacts only).
    static func deleteAll(prefix: String) {
        guard prefix.count >= 4 else { return }
        let req = CNContactFetchRequest(keysToFetch: keys); var gone: [CNMutableContact] = []
        try? store.enumerateContacts(with: req) { c, _ in if name(c).hasPrefix(prefix), let m = c.mutableCopy() as? CNMutableContact { gone.append(m) } }
        let r = CNSaveRequest(); gone.forEach { r.delete($0) }
        do { try store.execute(r); StatusLog.add("contacts: DEV deleted \(gone.count) \(prefix)* contacts") } catch { StatusLog.add("contacts: DEV delete failed: \(error)") }
    }
    /// Every contact the app can read (all of them, or the limited set), for the migration's matching.
    static func candidates() -> [ContactMatch.Candidate] {
        var out: [ContactMatch.Candidate] = []
        let req = CNContactFetchRequest(keysToFetch: keys)
        try? store.enumerateContacts(with: req) { c, _ in out.append(.init(key: c.identifier, name: name(c), emails: emails(c))) }
        return out
    }

    /// "Edit in Contacts": the system contact card (editable), over the frontmost screen; `done` after it closes.
    static func edit(_ key: String, done: @escaping @MainActor () -> Void) {
        guard let top = ContactPicker.topController() else { return }
        guard let c = try? store.unifiedContact(withIdentifier: key, keysToFetch: [CNContactViewController.descriptorForRequiredKeys()]) else {
            StatusLog.add("contacts: the contact is not readable"); return
        }
        let v = CNContactViewController(for: c)
        v.allowsEditing = true; v.allowsActions = true; v.contactStore = store
        let nav = CloseNav(rootViewController: v); nav.onClose = done
        v.navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .close, primaryAction: UIAction { [weak nav] _ in nav?.dismiss(animated: true) { done() } })
        top.present(nav, animated: true)
    }
    private final class CloseNav: UINavigationController {
        var onClose: (@MainActor () -> Void)?
        override func viewDidDisappear(_ animated: Bool) { super.viewDidDisappear(animated); if isBeingDismissed { onClose?() } }
    }
}

/// Shown where a person would be created while Contacts access is denied: why, and the way to Settings.
struct ContactsDeniedNote: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("contacts_denied")).font(.footnote).foregroundStyle(.secondary)
            Button(L("contacts_open_settings")) { OSContacts.openSettings() }.font(.footnote.bold())
        }.accessibilityIdentifier("contacts-denied")
    }
}

/// People list: "Add N people to Contacts?" for the people made before People became Contacts (dismissible).
struct ContactsMigrationCard: View {
    @ObservedObject private var store = PeopleStore.shared
    @AppStorage("contactsMigrationDismissed") private var dismissed = false
    @State private var running = false
    var body: some View {
        let n = store.appOnlyPeople.count
        if n > 0 && !dismissed && OSContacts.access != .denied {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L(n == 1 ? "contacts_migrate_one" : "contacts_migrate", n)).font(.subheadline.bold())
                    Text(L("contacts_migrate_text")).font(.footnote).foregroundStyle(.secondary)
                    HStack {
                        Button(L("contacts_migrate_later")) { dismissed = true }.foregroundStyle(.secondary)
                        Spacer()
                        if running { ProgressView() } else {
                            Button(L("contacts_migrate_add")) { running = true; Task { await store.migrateToContacts(); running = false } }.bold()
                                .accessibilityIdentifier("contacts-migrate")
                        }
                    }.buttonStyle(.borderless)
                }
            }
        }
    }
}

/// "Me" picks its own card (iOS has no public API for My Card, and the app never creates a contact named Me):
/// "This is me in Contacts…" opens the system picker; the link can be changed or removed.
struct MeContactRow: View {
    let me: Person
    @ObservedObject private var store = PeopleStore.shared
    var body: some View {
        let linked = me.contact?.platform == "ios"
        if linked { Text(L("contact_me_linked", me.contact?.name ?? "—")).font(.footnote).foregroundStyle(.secondary) }
        Button(L(linked ? "contact_me_change" : "contact_me_pick")) { pick() }.accessibilityIdentifier("contact-me")
        if linked { Button(L("contact_unlink")) { store.unlinkContact(me.id, removeEmails: true) }.foregroundStyle(.secondary) }
    }
    private func pick() {
        ContactPicker.present(needsEmail: false) { c, _ in
            if me.contact != nil { store.unlinkContact(me.id, removeEmails: true) }
            store.linkContact(me.id, c); store.syncContacts()
        }
    }
}
