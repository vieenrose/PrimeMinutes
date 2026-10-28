import SwiftUI
import Contacts
import ContactsUI

/// The system contact picker (docs/design/speaker-contacts.md §3.1, Android ContactPick): it runs out of process and
/// hands back only the contact the user taps, so the app needs no Contacts permission. Contacts without an e-mail are
/// greyed; a contact with one address is chosen in one tap, with several the user taps the one to use.
@MainActor enum ContactPicker {
    final class Delegate: NSObject, CNContactPickerDelegate {
        let done: @MainActor (PickedContact, String) -> Void
        let needsEmail: Bool
        init(_ needsEmail: Bool, _ done: @escaping @MainActor (PickedContact, String) -> Void) { self.needsEmail = needsEmail; self.done = done }
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
            let pick = (chosen ?? emails.first).map(EmailRules.normalize).flatMap { EmailRules.isValid($0) ? $0 : nil } ?? ""
            guard !pick.isEmpty || !needsEmail else { StatusLog.add("contacts: the chosen contact has no usable e-mail"); return }
            let name = (CNContactFormatter.string(from: c, style: .fullName) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let org = c.isKeyAvailable(CNContactOrganizationNameKey) ? c.organizationName.trimmingCharacters(in: .whitespacesAndNewlines) : ""
            let picked = PickedContact(name: name.isEmpty ? (org.isEmpty ? pick.nonEmpty : org) : name,
                                       emails: emails.map(EmailRules.normalize).filter(EmailRules.isValid),
                                       key: c.identifier, id: nil, org: org.isEmpty ? nil : org)
            done(picked, pick)
        }
    }
    static var active: Delegate?

    /// Shows the picker over the frontmost screen; `done` gets the contact and the address the user chose.
    static func present(needsEmail: Bool = true, _ done: @escaping @MainActor (PickedContact, String) -> Void) {
        if let f = Dev.env["VOX_CONTACT"], f.hasPrefix("fake:") {   // DEV: a picked contact without the picker, "fake:name|org|a,b|chosen"
            let a = f.dropFirst(5).split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            let emails = (a.count > 2 ? a[2] : "").split(separator: ",").map(String.init)
            let c = PickedContact(name: a.first, emails: emails, key: "dev-" + (a.first ?? ""), id: nil, org: a.count > 1 && !a[1].isEmpty ? a[1] : nil)
            StatusLog.add("contacts: DEV fake pick \(a.first ?? "")")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { done(c, a.count > 3 ? a[3] : emails.first ?? "") }
            return
        }
        guard let top = topController() else { StatusLog.add("contacts: no screen to show the picker over"); return }
        let d = Delegate(needsEmail, done); active = d
        let p = CNContactPickerViewController()
        p.delegate = d
        if needsEmail {   // an address for a mail; a person (speaker picker) is any contact, picked in one tap
            p.displayedPropertyKeys = [CNContactEmailAddressesKey]
            p.predicateForEnablingContact = NSPredicate(format: "emailAddresses.@count > 0")
            p.predicateForSelectionOfContact = NSPredicate(format: "emailAddresses.@count == 1")
            p.predicateForSelectionOfProperty = NSPredicate(format: "key == 'emailAddresses'")
        }
        top.present(p, animated: true)
        StatusLog.add("contacts: picker shown over \(type(of: top))")
        if let t = Double(Dev.env["VOX_CONTACT_CLOSE"] ?? "") {   // DEV: closes the picker, nothing picked
            DispatchQueue.main.asyncAfter(deadline: .now() + t) { p.dismiss(animated: true); active = nil; StatusLog.add("contacts: DEV picker closed") }
        }
    }

    static func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        var c = scenes.flatMap(\.windows).first { $0.isKeyWindow }?.rootViewController
        while let p = c?.presentedViewController, !p.isBeingDismissed { c = p }
        return c
    }
}

/// A person's contact side on the person page: People == Contacts, so the person IS its contact. Addresses are
/// edited in Contacts ("Edit in Contacts", the system card) and read back here; the first one is used when sending.
/// A person without a contact (made before, or its contact deleted) gets "Add to Contacts" (matched, not duplicated).
struct PersonContactSections: View {
    let person: Person
    /// "Also linked to X: the same person?" → Merge.
    let onMergeInto: (Person) -> Void
    @ObservedObject private var store = PeopleStore.shared

    var body: some View {
        let p = person, linked = p.contact?.platform == "ios"
        Section {
            if p.me && OSContacts.access != .denied {
                MeContactRow(me: p)
            } else if linked {
                Text(L("contact_is_contact")).font(.subheadline).foregroundStyle(.secondary)
                Button { OSContacts.edit(p.contact!.key) { store.syncContacts() } } label: { Label(L("contact_edit"), systemImage: "person.crop.circle") }
                    .accessibilityIdentifier("contact-edit")
            } else if OSContacts.access == .denied {
                ContactsDeniedNote()
            } else {
                Text(L("contact_not_in_contacts")).font(.subheadline).foregroundStyle(.secondary)
                Button { Task { await store.migrateToContacts([p]) } } label: { Label(L("contact_add_one"), systemImage: "person.crop.circle.badge.plus") }
                    .accessibilityIdentifier("contact-add")
            }
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
                HStack {
                    Text(e.address).lineLimit(1)
                    Spacer()
                    if i == 0 && p.emails.count > 1 { Image(systemName: "star.fill").foregroundStyle(Color.accentColor).accessibilityLabel(L("email_default")) }
                }
            }
            if p.emails.isEmpty { Text(L(linked ? "email_none_contact" : "email_none")).font(.footnote).foregroundStyle(.secondary) }
        } header: { Label(L("email_card_title"), systemImage: "envelope") }
        .task { store.syncContacts() }
    }
}
