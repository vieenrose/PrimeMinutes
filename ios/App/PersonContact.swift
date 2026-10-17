import SwiftUI
import Contacts
import ContactsUI

/// The system contact picker (docs/design/speaker-contacts.md §3.1, Android ContactPick): it runs out of process and
/// hands back only the contact the user taps, so the app needs no Contacts permission. Contacts without an e-mail are
/// greyed; a contact with one address is chosen in one tap, with several the user taps the one to use.
@MainActor enum ContactPicker {
    final class Delegate: NSObject, CNContactPickerDelegate {
        let done: @MainActor (PickedContact, String) -> Void
        init(_ done: @escaping @MainActor (PickedContact, String) -> Void) { self.done = done }
        nonisolated func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
            MainActor.assumeIsolated { finish(contact, nil) }
        }
        nonisolated func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
            let v = contactProperty.value as? String, c = contactProperty.contact
            MainActor.assumeIsolated { finish(c, v) }
        }
        nonisolated func contactPickerDidCancel(_ picker: CNContactPickerViewController) { MainActor.assumeIsolated { ContactPicker.active = nil } }
        @MainActor private func finish(_ c: CNContact, _ chosen: String?) {
            defer { ContactPicker.active = nil }
            let emails = c.isKeyAvailable(CNContactEmailAddressesKey) ? c.emailAddresses.map { $0.value as String } : []
            let pick = (chosen ?? emails.first).map(EmailRules.normalize)
            guard let pick, EmailRules.isValid(pick) else { StatusLog.add("contacts: the chosen contact has no usable e-mail"); return }
            let name = (CNContactFormatter.string(from: c, style: .fullName) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let org = c.isKeyAvailable(CNContactOrganizationNameKey) ? c.organizationName.trimmingCharacters(in: .whitespacesAndNewlines) : ""
            let picked = PickedContact(name: name.isEmpty ? (org.isEmpty ? pick : org) : name,
                                       emails: emails.map(EmailRules.normalize).filter(EmailRules.isValid),
                                       key: c.identifier, id: nil, org: org.isEmpty ? nil : org)
            done(picked, pick)
        }
    }
    static var active: Delegate?

    /// Shows the picker over the frontmost screen; `done` gets the contact and the address the user chose.
    static func present(_ done: @escaping @MainActor (PickedContact, String) -> Void) {
        guard let top = topController() else { return }
        let d = Delegate(done); active = d
        let p = CNContactPickerViewController()
        p.delegate = d
        p.displayedPropertyKeys = [CNContactEmailAddressesKey]
        p.predicateForEnablingContact = NSPredicate(format: "emailAddresses.@count > 0")
        p.predicateForSelectionOfContact = NSPredicate(format: "emailAddresses.@count == 1")
        p.predicateForSelectionOfProperty = NSPredicate(format: "key == 'emailAddresses'")
        top.present(p, animated: true)
    }

    static func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        var c = scenes.flatMap(\.windows).first { $0.isKeyWindow }?.rootViewController
        while let p = c?.presentedViewController, !p.isBeingDismissed { c = p }
        return c
    }
}

/// A person's contact side on the person page (Android PersonContactCards, mockup 01): the address-book link and the
/// e-mail addresses, the first one (★) used when sending. A contact never renames the person.
struct PersonContactSections: View {
    let person: Person
    /// "Also linked to X: the same person?" → Merge.
    let onMergeInto: (Person) -> Void
    @ObservedObject private var store = PeopleStore.shared
    @State private var adding = false
    @State private var draft = ""
    @State private var unlinking = false
    @State private var replaceAsk: (PickedContact, String)?

    var body: some View {
        let p = person
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(contactLine(p)).font(.subheadline).foregroundStyle(.secondary)
                if p.contact == nil { Text(L("contact_choose_hint")).font(.caption).foregroundStyle(.secondary) }
            }
            Button(L(p.contact == nil ? "contact_choose" : "contact_update")) {
                ContactPicker.present { c, chosen in
                    if let old = p.contact, old.platform == c.platform, old.key != c.key { replaceAsk = (c, chosen) }
                    else { store.linkContact(p.id, c, chosen: chosen) }
                }
            }.accessibilityIdentifier("contact-pick")
            if p.contact != nil { Button(L("contact_unlink")) { unlinking = true }.foregroundStyle(.secondary) }
            if let o = PeopleOps.sameContact(store.doc, p).first {
                HStack {
                    Text(L("contact_also_linked", o.name)).font(.footnote).foregroundStyle(.orange)
                    Spacer()
                    Button(L("contact_merge")) { onMergeInto(o) }.buttonStyle(.borderless)
                }
            }
        } header: { Label(L("contact_card_title"), systemImage: "person.crop.rectangle") }
        Section {
            if p.me { Text(L("email_me_hint")).font(.footnote).foregroundStyle(.secondary) }
            ForEach(Array(p.emails.enumerated()), id: \.element.address) { i, e in
                let isDefault = i == 0, showDefault = p.emails.count > 1
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(e.address).lineLimit(1)
                        Text(([L(e.from == PersonEmail.fromContact ? "email_from_contact" : "email_from_user")] + (isDefault && showDefault ? [L("email_default")] : [])).joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if isDefault && showDefault { Image(systemName: "star.fill").foregroundStyle(Color.accentColor).accessibilityLabel(L("email_default")) }
                    Menu {
                        if !isDefault { Button(L("email_make_default")) { store.makeDefaultEmail(p.id, e.address) } }
                        Button(L("email_remove"), role: .destructive) { store.removeEmail(p.id, e.address) }
                    } label: { Image(systemName: "ellipsis.circle").foregroundStyle(.secondary) }.accessibilityLabel(L("cd_more_options"))
                }
            }
            Button { draft = ""; adding = true } label: { Label(L("email_add"), systemImage: "plus") }.accessibilityIdentifier("email-add")
        } header: { Label(L("email_card_title"), systemImage: "envelope") }
        .alert(L("email_add"), isPresented: $adding) {
            TextField("name@example.com", text: $draft).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button(L("people_save")) {
                let a = EmailRules.normalize(draft)
                if EmailRules.isValid(a) { store.addEmail(p.id, a) } else { StatusLog.add("people: not an e-mail address") }
            }
            Button(L("cancel"), role: .cancel) {}
        }
        .alert(L("contact_unlink_title", p.name), isPresented: $unlinking) {
            Button(L("contact_unlink")) { store.unlinkContact(p.id, removeEmails: false) }
            if p.emails.contains(where: { $0.from == PersonEmail.fromContact }) {
                Button(L("contact_unlink_remove"), role: .destructive) { store.unlinkContact(p.id, removeEmails: true) }
            }
            Button(L("cancel"), role: .cancel) {}
        } message: { Text(L("contact_unlink_text")) }
        .alert(L("contact_replace", p.contact?.name ?? "—"), isPresented: Binding(get: { replaceAsk != nil }, set: { if !$0 { replaceAsk = nil } }), presenting: replaceAsk) { a in
            Button(L("contact_replace_yes")) { replaceAsk = nil; store.linkContact(p.id, a.0, chosen: a.1) }
            Button(L("cancel"), role: .cancel) { replaceAsk = nil }
        }
    }

    private func contactLine(_ p: Person) -> String {
        guard let c = p.contact else { return L("contact_not_linked") }
        if c.platform != "ios" { return L("contact_other_platform") }
        let who = [c.name, c.org.map { "(\($0))" }].compactMap { $0 }.joined(separator: " ")
        let when = Date(timeIntervalSince1970: TimeInterval(c.synced)).formatted(Date.FormatStyle(date: .numeric, time: .omitted).locale(AppLanguage.current.locale))
        return L("contact_linked_to", who.isEmpty ? "—" : who, when)
    }
}
