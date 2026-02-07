import Link from 'next/link'

export default function TermsPage() {
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
        <h1 className="text-4xl font-bold mb-4 dark:text-white">Terms of Service</h1>
        <p className="text-slate-600 dark:text-slate-400 mb-8">Last updated: January 29, 2026</p>

        <div className="prose prose-slate dark:prose-invert max-w-none">
          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">1. Acceptance of Terms</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              By downloading, installing, or using SyncFlow, you agree to be bound by these Terms of Service.
              If you do not agree to these terms, do not use the service.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">2. Description of Service</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              SyncFlow provides a service that syncs SMS messages, contacts, call history, and files between
              your Android device and Mac computer. The service includes both free and premium features.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">3. User Accounts</h2>
            <ul className="list-disc pl-6 mb-4 text-slate-700 dark:text-slate-300 space-y-2">
              <li>You are responsible for maintaining the security of your account credentials</li>
              <li>You must provide accurate and complete information when creating an account</li>
              <li>You are responsible for all activities that occur under your account</li>
              <li>You must notify us immediately of any unauthorized access</li>
            </ul>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">4. Acceptable Use</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">You agree NOT to:</p>
            <ul className="list-disc pl-6 mb-4 text-slate-700 dark:text-slate-300 space-y-2">
              <li>Use the service for any illegal purpose or in violation of any laws</li>
              <li>Attempt to gain unauthorized access to our systems or networks</li>
              <li>Interfere with or disrupt the service or servers</li>
              <li>Upload malware, viruses, or other malicious code</li>
              <li>Impersonate any person or entity</li>
              <li>Spam, harass, or abuse other users</li>
              <li>Reverse engineer, decompile, or disassemble the software</li>
            </ul>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">5. Subscription and Payment</h2>

            <h3 className="text-xl font-semibold mb-3 dark:text-white">Free Tier</h3>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              The free tier includes basic messaging sync with limited storage (50MB upload/month, 50MB total storage).
            </p>

            <h3 className="text-xl font-semibold mb-3 dark:text-white">Premium Subscription</h3>
            <ul className="list-disc pl-6 mb-4 text-slate-700 dark:text-slate-300 space-y-2">
              <li>Premium features require a paid subscription ($4.99/month or as displayed in-app)</li>
              <li>Subscriptions automatically renew unless cancelled 24 hours before the end of the current period</li>
              <li>Payments are processed through Apple's App Store or Google Play Store</li>
              <li>Prices may vary by region and are subject to change</li>
              <li>Refunds are handled according to Apple/Google's policies</li>
            </ul>

            <h3 className="text-xl font-semibold mb-3 dark:text-white">Free Trial</h3>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              New users may receive a 7-day free trial of premium features. You will be charged when the trial ends
              unless you cancel before that time.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">6. Data Usage and Storage</h2>
            <ul className="list-disc pl-6 mb-4 text-slate-700 dark:text-slate-300 space-y-2">
              <li>You retain ownership of all data you sync through SyncFlow</li>
              <li>We reserve the right to implement storage limits and usage quotas</li>
              <li>Files and attachments may be automatically deleted after 30-90 days</li>
              <li>We may delete inactive accounts after 12 months of inactivity</li>
            </ul>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">7. Intellectual Property</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              SyncFlow and all related trademarks, logos, and service marks are the property of SyncFlow.
              You may not use our intellectual property without our written permission.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">8. Disclaimer of Warranties</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              THE SERVICE IS PROVIDED "AS IS" WITHOUT WARRANTIES OF ANY KIND, EXPRESS OR IMPLIED.
              WE DO NOT GUARANTEE THAT THE SERVICE WILL BE UNINTERRUPTED, SECURE, OR ERROR-FREE.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">9. Limitation of Liability</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              TO THE MAXIMUM EXTENT PERMITTED BY LAW, SYNCFLOW SHALL NOT BE LIABLE FOR ANY INDIRECT,
              INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES, INCLUDING LOSS OF DATA, REVENUE,
              OR PROFITS, ARISING FROM YOUR USE OF THE SERVICE.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">10. Termination</h2>
            <ul className="list-disc pl-6 mb-4 text-slate-700 dark:text-slate-300 space-y-2">
              <li>You may terminate your account at any time through the app settings</li>
              <li>We may suspend or terminate your account for violations of these terms</li>
              <li>Upon termination, your data may be deleted within 30 days</li>
              <li>Some provisions of these terms survive termination</li>
            </ul>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">11. Changes to Terms</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              We reserve the right to modify these terms at any time. We will notify you of material changes
              through the app or by email. Continued use of the service after changes constitutes acceptance
              of the new terms.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">12. Governing Law</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              These terms are governed by the laws of the United States and the State of California,
              without regard to conflict of law provisions.
            </p>
          </section>

          <section className="mb-8">
            <h2 className="text-2xl font-semibold mb-4 dark:text-white">13. Contact</h2>
            <p className="text-slate-700 dark:text-slate-300 mb-4">
              For questions about these Terms of Service, contact us at:
            </p>
            <ul className="list-none text-slate-700 dark:text-slate-300 space-y-2">
              <li><strong>Email:</strong> <a href="mailto:legal@syncflow.app" className="text-blue-600 hover:underline">legal@syncflow.app</a></li>
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
