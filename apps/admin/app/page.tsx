export default function Page() {
  return (
    <main>
      <header>
        <span className="logo">routiqo.</span>
        <span>MODERATION WORKSPACE</span>
      </header>
      <section>
        <div className="lock" aria-hidden="true">
          ⌑
        </div>
        <p className="eyebrow">RESTRICTED WORKSPACE</p>
        <h1>
          A safer journey
          <br />
          starts with trust.
        </h1>
        <p className="description">
          The moderation workspace is being prepared. Administrative access is not enabled in this
          foundation build.
        </p>
        <div className="notice">
          <strong>No moderation data is exposed.</strong>
          <p>
            Role-based sign-in, report queues, enforcement actions, and audit trails will be
            connected before this workspace becomes operational.
          </p>
        </div>
      </section>
      <footer>Routiqo · Safety by design</footer>
    </main>
  );
}
