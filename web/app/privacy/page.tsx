import Link from 'next/link'

export default function PrivacyPage() {
  return (
    <div className="min-h-screen bg-white dark:bg-slate-900">
      {/* Header */}
      <header className="border-b border-slate-200 dark:border-slate-700">
        <div className="max-w-4xl mx-auto px-4 py-4">
          <Link href="/" className="text-2xl font-bold bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text text-transparent">
            SyncFlow
          </Link>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-4 py-16">
        <h1 className="text-4xl font-bold mb-4 dark:text-white">Privacy Policy</h1>
        <p className="text-slate-600 dark:text-slate-400 mb-8">Last updated: January 29, 2026</p>

        <div className="prose prose-slate dark:prose-invert max-w-none">
          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">Introduction</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              SyncFlow ("we," "our," or "us") respects your privacy and is committed to protecting your personal data.
              This privacy policy explains how we collect, use, and safeguard your information when you use the SyncFlow
              application for macOS and Android.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">Information We Collect</h2>

            <h3 className="text-xl font-semibold mb-3 dark:text-white">Data You Provide</h3>
            <ul className="list-disc pl-6 mb-4 text-slate-700 dark:text-slate-300 space-y-2">
              <li><strong>SMS Messages:</strong> We sync your SMS/MMS messages between your Android device and Mac</li>
              <li><strong>Contacts:</strong> Contact names and phone numbers from your Android device</li>
              <li><strong>Call History:</strong> Phone numbers, call duration, and timestamps</li>
              <li><strong>Photos (Premium):</strong> Photos you choose to sync (Premium feature)</li>
              <li><strong>Files:</strong> Files you transfer between devices</li>
              <li><strong>Account Information:</strong> Email address for authentication</li>
            </ul>

            <h3 className="text-xl font-semibold mb-3 dark:text-white">Automatically Collected Data</h3>
            <ul className="list-disc pl-6 mb-4 text-slate-700 dark:text-slate-300 space-y-2">
              <li><strong>Device Information:</strong> Device model, OS version, app version</li>
              <li><strong>Usage Data:</strong> Feature usage statistics, sync frequency</li>
              <li><strong>Error Logs:</strong> Crash reports and error diagnostics (anonymous)</li>
            </ul>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">How We Use Your Information</h2>
            <ul className="list-disc pl-6 mb-4 text-slate-700 dark:text-slate-300 space-y-2">
              <li><strong>Core Functionality:</strong> Sync messages, contacts, and files between your devices</li>
              <li><strong>Service Improvement:</strong> Analyze usage patterns to improve features</li>
              <li><strong>Customer Support:</strong> Respond to your support requests</li>
              <li><strong>Security:</strong> Detect and prevent fraud, abuse, and security issues</li>
              <li><strong>Legal Compliance:</strong> Comply with applicable laws and regulations</li>
            </ul>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">Data Storage and Security</h2>

            <h3 className="text-xl font-semibold mb-3 dark:text-white">Encryption</h3>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              All data transmitted between your devices and our servers is encrypted using industry-standard
              TLS (Transport Layer Security). Message content can be optionally encrypted end-to-end (E2EE),
              meaning only you can read your messages.
            </p>

            <h3 className="text-xl font-semibold mb-3 dark:text-white">Storage Location</h3>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              Your data is stored on Firebase (Google Cloud) and Cloudflare R2 servers located in the United States.
              We implement appropriate technical and organizational measures to protect your data.
            </p>

            <h3 className="text-xl font-semibold mb-3 dark:text-white">Data Retention</h3>
            <ul className="list-disc pl-6 mb-4 text-slate-700 dark:text-slate-300 space-y-2">
              <li><strong>Messages:</strong> Retained until you delete them or delete your account</li>
              <li><strong>Files/Photos:</strong> Deleted after 30-90 days unless you have Premium</li>
              <li><strong>Account Data:</strong> Retained until you request account deletion</li>
              <li><strong>Usage Logs:</strong> Deleted after 90 days</li>
            </ul>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">Data Sharing</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              We do not sell your personal data. We may share your data only in the following circumstances:
            </p>
            <ul className="list-disc pl-6 mb-4 text-slate-700 dark:text-slate-300 space-y-2">
              <li><strong>Service Providers:</strong> Firebase (Google), Cloudflare for hosting and storage</li>
              <li><strong>Legal Requirements:</strong> When required by law or to protect our legal rights</li>
              <li><strong>Business Transfers:</strong> In connection with a merger, acquisition, or sale of assets</li>
              <li><strong>With Your Consent:</strong> When you explicitly consent to sharing</li>
            </ul>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">Your Rights</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">You have the right to:</p>
            <ul className="list-disc pl-6 mb-4 text-slate-700 dark:text-slate-300 space-y-2">
              <li><strong>Access:</strong> Request a copy of your personal data</li>
              <li><strong>Correction:</strong> Update or correct inaccurate data</li>
              <li><strong>Deletion:</strong> Request deletion of your account and data</li>
              <li><strong>Export:</strong> Download your data in a portable format</li>
              <li><strong>Opt-out:</strong> Disable optional features like analytics</li>
            </ul>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              To exercise these rights, go to Settings → Privacy in the app or email us at{' '}
              <a href="mailto:privacy@syncflow.app" className="text-blue-600 hover:underline">privacy@syncflow.app</a>
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">Children's Privacy</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              SyncFlow is not intended for use by children under 13 years of age. We do not knowingly collect
              personal information from children under 13. If you believe we have collected data from a child,
              please contact us immediately.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">International Users</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              If you are accessing SyncFlow from outside the United States, please note that your data will be
              transferred to and processed in the United States. By using SyncFlow, you consent to this transfer.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">Changes to This Policy</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              We may update this privacy policy from time to time. We will notify you of significant changes
              by posting a notice in the app or sending you an email. Your continued use of SyncFlow after
              changes are posted constitutes acceptance of the updated policy.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">Contact Us</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              If you have questions about this privacy policy or our data practices, please contact us:
            </p>
            <ul className="list-none text-slate-700 dark:text-slate-300 space-y-2">
              <li><strong>Email:</strong> <a href="mailto:privacy@syncflow.app" className="text-blue-600 hover:underline">privacy@syncflow.app</a></li>
              <li><strong>Support:</strong> <a href="mailto:support@syncflow.app" className="text-blue-600 hover:underline">support@syncflow.app</a></li>
            </ul>
          </section>
        </div>

        <div className="mt-12 pt-8 border-t border-slate-200 dark:border-slate-700">
          <Link href="/download" className="text-blue-600 hover:underline">← Back to Download</Link>
        </div>
      </main>
    </div>
  )
}
