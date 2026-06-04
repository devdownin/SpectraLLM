/* Spectra — interactions légères, sans dépendance.
   1) Révélation au défilement (IntersectionObserver)
   2) Bascule des détails (liste des 11 stratégies RAG)
   3) Ombre de la barre de navigation au scroll
*/
(function () {
  "use strict";

  /* 1) Scroll reveal -------------------------------------------------- */
  var reveals = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window && reveals.length) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-visible");
          io.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12, rootMargin: "0px 0px -40px 0px" });
    reveals.forEach(function (el) { io.observe(el); });
  } else {
    // Pas de support : tout afficher.
    reveals.forEach(function (el) { el.classList.add("is-visible"); });
  }

  /* 2) Toggle détails ------------------------------------------------- */
  document.querySelectorAll("[data-toggle]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var target = document.getElementById(btn.getAttribute("data-toggle"));
      if (!target) return;
      var open = target.hasAttribute("hidden");
      if (open) { target.removeAttribute("hidden"); } else { target.setAttribute("hidden", ""); }
      btn.setAttribute("aria-expanded", String(open));
      btn.textContent = open ? "Masquer les détails ▴" : "Détailler les 11 stratégies ▾";
    });
  });

  /* 3) Ombre nav au scroll ------------------------------------------- */
  var nav = document.getElementById("nav");
  if (nav) {
    var onScroll = function () {
      nav.style.boxShadow = window.scrollY > 8 ? "0 8px 24px -16px rgba(0,0,0,.9)" : "none";
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
  }
})();
